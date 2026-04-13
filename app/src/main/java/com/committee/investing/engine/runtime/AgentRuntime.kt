package com.committee.investing.engine.runtime

import android.util.Log
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.repository.EventRepository
import com.committee.investing.engine.AgentPool
import com.committee.investing.engine.LlmConfig
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.MicContext
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.SpeechRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  AgentRuntime（v5 — 修复 UI 不更新）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v5 修复：
 *    1. Blackboard 改为不可变 data class → StateFlow 正确检测变化
 *    2. 去掉重复 LLM 调用：respond() 只做决策，content 直接从 response 取
 *       callLlmStreaming 只用于流式显示（和 respond 共用同一次 LLM 结果）
 *    3. speeches 用新 list 赋值确保 StateFlow 触发
 */
class AgentRuntime(
    private val agentPool: AgentPool,
    private val supervisor: SupervisorAgent,
    private val agents: List<Agent>,
    private val configProvider: () -> LlmConfig,
    private val repository: EventRepository,
) {
    companion object {
        private const val TAG = "AgentRuntime"
        private const val ABSOLUTE_MAX_ROUNDS = 20
        private const val ACTIVATION_K = 2
        private const val SUMMARY_INTERVAL = 2
    }

    private val _board = MutableStateFlow(Blackboard())
    val board: StateFlow<Blackboard> = _board.asStateFlow()

    private val _speeches = MutableStateFlow<List<SpeechRecord>>(emptyList())
    val speeches: StateFlow<List<SpeechRecord>> = _speeches.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _runtimeLog = MutableStateFlow<List<String>>(emptyList())
    val runtimeLog: StateFlow<List<String>> = _runtimeLog.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null
    private var currentTraceId: String = ""

    // ── 公开接口 ─────────────────────────────────────────────────

    fun startMeeting(subject: String) {
        if (_isRunning.value) return
        currentTraceId = "mtg_${System.currentTimeMillis()}"
        _board.value = Blackboard(subject = subject)
        _speeches.value = emptyList()
        _runtimeLog.value = emptyList()
        log("[启动] traceId=$currentTraceId 标的=$subject")

        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = currentTraceId,
                subject = subject,
                startTime = System.currentTimeMillis(),
                currentState = "ANALYSIS",
                currentRound = 1,
            ))
        }

        runJob = scope.launch {
            _isRunning.value = true
            try {
                runLoop()
            } catch (e: CancellationException) {
                log("[中断] 会议被取消")
                updateSessionFinished()
            } catch (e: Exception) {
                log("[错误] ${e.javaClass.simpleName}: ${e.message}")
                updateSessionFinished()
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun cancelMeeting() {
        runJob?.cancel()
        _board.value = _board.value.copy(finished = true, phase = BoardPhase.IDLE)
        _isRunning.value = false
    }

    fun confirmExecution() {
        val b = _board.value
        if (b.phase == BoardPhase.EXECUTION) {
            _board.value = b.copy(finished = true, phase = BoardPhase.DONE)
        }
    }

    fun destroy() {
        runJob?.cancel()
        scope.cancel()
    }

    // ── 核心循环 ─────────────────────────────────────────────────

    private suspend fun runLoop() {
        while (!_board.value.finished) {
            val board = _board.value

            if (board.round > ABSOLUTE_MAX_ROUNDS) {
                log("[安全阀] 超过 $ABSOLUTE_MAX_ROUNDS 轮")
                finishWithRating("Hold")
                break
            }

            log("[Round ${board.round}] ────────────────────────────")

            // ── ① Summary Memory ──────────────────────────────────────
            if (board.round > 1 && board.round - board.lastSummaryRound >= SUMMARY_INTERVAL) {
                updateSummary()
            }

            // ── ② Supervisor 结束判断（降频） ──────────────────────
            if (board.round % 2 == 0 || board.consensus || board.round >= 4) {
                val shouldFinish = askSupervisorFinish(board)
                log("[Supervisor] shouldFinish=$shouldFinish")
                if (shouldFinish) {
                    val rating = doRating()
                    finishWithRating(rating ?: "Hold")
                    break
                }
            }

            // ── ③ Supervisor 点评（降频） ──────────────────────────
            if (shouldSupervisorComment(board)) {
                val comment = quickLlm(supervisor.buildSupervisionPrompt(board))
                if (comment.isNotBlank() && comment.length > 10) {
                    addBoardMessage(supervisor.role, comment, emptyList())
                }
            }

            // ── ④ 加权选择 Agent ──────────────────────────────────
            val eligible = agents.filter { it.eligible(_board.value) }
            log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                log("[eligible] 无人有资格")
                advanceRound()
                continue
            }

            val scored = eligible.map { it to it.scoring(_board.value) }
                .sortedByDescending { it.second }
            scored.forEach { (agent, score) ->
                log("[score] ${agent.role} = ${"%.1f".format(score)}")
            }

            val topK = scored.take(ACTIVATION_K)
            val picked = weightedRandom(topK)
            log("[picked] ${picked.role}")

            // ── ⑤ 统一 LLM 调用 ──────────────────────────────────
            try {
                val response = picked.respond(_board.value, ::quickLlm)
                log("[${picked.role}] speak=${response.wantsToSpeak} vote=${response.voteBull}")

                if (response.wantsToSpeak && response.content.isNotBlank()) {
                    // 🔥 流式输出 — 用 respond 已有的 content 做流式展示
                    val role = AgentRole.fromId(picked.role) ?: AgentRole.SUPERVISOR
                    streamToSpeeches(role, response.content, _board.value.round)

                    // 写入 Board（不可变更新）
                    val tags = response.normalizedTags.map { it.name }
                    addBoardMessage(picked.role, response.content, tags)

                    // 投票
                    if (response.voteBull != null) {
                        _board.value = _board.value.copy(
                            votes = _board.value.votes + BoardVote(
                                role = picked.role,
                                agree = response.voteBull,
                                round = _board.value.round,
                            )
                        )
                        log("[Vote] ${picked.role} = ${if (response.voteBull) "看多" else "看空"}")
                    }

                    // 共识
                    val b = _board.value
                    if (b.hasConsensus() && !b.consensus) {
                        _board.value = b.copy(consensus = true)
                        log("[共识] 看多${(b.bullRatio() * 100).toInt()}%")
                    }

                    updatePhase()
                } else {
                    log("[${picked.role}] SPEAK=NO")
                }
            } catch (e: Exception) {
                log("[${picked.role}] 错误: ${e.message}")
            }

            advanceRound()
        }
    }

    // ── 流式输出到 Speeches ───────────────────────────────────

    /**
     * 🔥 将已有 content 以"流式打字"效果推送到 _speeches
     * 模拟逐字显示（不需要额外 LLM 调用）
     */
    private suspend fun streamToSpeeches(role: AgentRole, content: String, round: Int) {
        // 先添加空记录
        val recordId = "sp_${System.currentTimeMillis()}"
        _speeches.value = _speeches.value + SpeechRecord(
            id = recordId,
            agent = role,
            content = "",
            summary = "",
            round = round,
            isStreaming = true,
        )

        // 逐段推送（模拟流式效果）
        val chunkSize = 8
        var idx = 0
        while (idx < content.length) {
            val end = minOf(idx + chunkSize, content.length)
            val partial = content.substring(0, end)

            _speeches.value = _speeches.value.map {
                if (it.id == recordId) it.copy(content = partial, isStreaming = true)
                else it
            }

            idx = end
            delay(16) // ~60fps
        }

        // 完成流式
        _speeches.value = _speeches.value.map {
            if (it.id == recordId) it.copy(content = content, isStreaming = false)
            else it
        }

        // 持久化
        val finalRecord = _speeches.value.last { it.id == recordId }
        persistSpeech(finalRecord)
    }

    // ── 加权随机 ─────────────────────────────────────────────

    private fun weightedRandom(scored: List<Pair<Agent, Double>>): Agent {
        if (scored.size == 1) return scored[0].first
        val minScore = scored.minOf { it.second }
        val adjusted = scored.map { it.first to (it.second - minScore + 0.1) }
        val total = adjusted.sumOf { it.second }
        var rand = kotlin.random.Random.nextDouble() * total
        for ((agent, weight) in adjusted) {
            rand -= weight
            if (rand <= 0) return agent
        }
        return adjusted.last().first
    }

    // ── Summary Memory ───────────────────────────────────────

    private suspend fun updateSummary() {
        val board = _board.value
        if (board.messages.size < 3) return
        log("[Summary] 生成讨论摘要（${board.messages.size}条消息）...")
        val prompt = supervisor.buildSummaryPrompt(board)
        val newSummary = quickLlm(prompt)
        if (newSummary.isNotBlank()) {
            _board.value = board.copy(
                summary = newSummary,
                lastSummaryRound = board.round,
            )
            log("[Summary] 已更新（${newSummary.length}字）")
        }
    }

    // ── Supervisor 降频 ──────────────────────────────────────

    private fun shouldSupervisorComment(board: Blackboard): Boolean {
        if (board.round <= 1) return false
        if (board.messages.any { it.role == "supervisor" && it.round == board.round }) return false
        if (board.consensus) return false
        return board.messages.count { it.role != "supervisor" } >= 3
    }

    private suspend fun askSupervisorFinish(board: Blackboard): Boolean {
        val prompt = supervisor.buildFinishPrompt(board)
        val response = quickLlm(prompt)
        val r = response.trim().uppercase()
        return r.startsWith("YES") || r.startsWith("Y") || r.startsWith("是")
    }

    // ── LLM 调用 ─────────────────────────────────────────────

    private suspend fun quickLlm(prompt: String): String {
        val sb = StringBuilder()
        val role = AgentRole.SUPERVISOR
        val ctx = MicContext(
            traceId = "q_${System.currentTimeMillis()}",
            causedBy = "system",
            round = _board.value.round,
            phase = MeetingState.IDLE,
            subject = _board.value.subject,
            agentRole = role,
            task = "decision",
        )
        try {
            agentPool.callAgentStreaming(role, ctx, systemPromptOverride = prompt)
                .collect { sb.append(it) }
        } catch (e: Exception) {
            log("[quickLlm错误] ${e.message}")
        }
        return sb.toString()
    }

    // ── Board 操作（不可变）───────────────────────────────────

    /**
     * 🔥 不可变更新：创建新 list，确保 StateFlow 检测到变化
     */
    private fun addBoardMessage(role: String, content: String, rawTags: List<String>) {
        val b = _board.value
        val newMsg = BoardMessage(
            role = role, content = content,
            round = b.round, rawTags = rawTags,
        )
        _board.value = b.copy(messages = b.messages + newMsg)
        log("[Board] +$role tags=$rawTags: ${content.take(60)}...")
    }

    private fun updatePhase() {
        val b = _board.value
        val newPhase = b.inferPhase()
        if (b.phase != newPhase) {
            _board.value = b.copy(phase = newPhase)
            log("[Phase] ${b.phase} → $newPhase")
        }
    }

    private fun advanceRound() {
        if (!_board.value.finished) {
            _board.value = _board.value.copy(round = _board.value.round + 1)
        }
    }

    private suspend fun doRating(): String? {
        val prompt = supervisor.buildRatingPrompt(_board.value)
        val content = quickLlm(prompt)
        return parseRating(content)
    }

    private fun finishWithRating(rating: String) {
        _board.value = _board.value.copy(
            finished = true,
            finalRating = rating,
            phase = BoardPhase.RATING,
        )
        log("[评级] 最终评级: $rating")
        if (agents.any { it.role == "executor" }) {
            _board.value = _board.value.copy(phase = BoardPhase.EXECUTION)
        }
        updateSessionFinished()
    }

    private fun parseRating(content: String): String? {
        val regex = Regex("""最终评级.*?(Buy|Overweight|Hold\+|Hold|Underweight|Sell)""")
        return regex.find(content)?.groupValues?.getOrNull(1)
    }

    private fun log(msg: String) {
        Log.e(TAG, msg)
        _runtimeLog.value = (_runtimeLog.value + msg).takeLast(200)
    }

    private fun updateSessionFinished() {
        if (currentTraceId.isBlank()) return
        val b = _board.value
        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = currentTraceId,
                subject = b.subject,
                startTime = System.currentTimeMillis(),
                currentState = b.phase.name,
                currentRound = b.round,
                rating = b.finalRating,
                isCompleted = b.finished,
            ))
            repository.saveSpeeches(currentTraceId, _speeches.value)
        }
    }

    private fun persistSpeech(speech: SpeechRecord) {
        if (currentTraceId.isBlank() || speech.isStreaming) return
        scope.launch {
            repository.saveSpeeches(currentTraceId, listOf(speech))
        }
    }
}
