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
 *  AgentRuntime（v4 — 系统稳定性层）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v4 新增：
 *    1. Summary Memory —— 每 2 轮自动摘要，防止信息丢失
 *    2. 加权选择 —— scoring() + topK + weighted random
 *    3. 标签标准化 —— BoardMessage 自动 normalize
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
        private const val SUMMARY_INTERVAL = 2   // 🔥 每 2 轮更新一次摘要
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

            // ── ① Summary Memory（每 N 轮更新摘要） ──────────────
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
                    writeToBoard(supervisor.role, comment, emptyList())
                }
            }

            // ── ④ 🔥 加权选择 Agent ──────────────────────────────
            val eligible = agents.filter { it.eligible(_board.value) }
            log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                log("[eligible] 无人有资格")
                advanceRound()
                continue
            }

            // 🔥 加权评分 + topK + 加权随机
            val scored = eligible.map { it to it.scoring(_board.value) }
                .sortedByDescending { it.second }
            scored.forEach { (agent, score) ->
                log("[score] ${agent.role} = ${"%.1f".format(score)}")
            }

            // 取 topK，然后按分数加权随机选
            val topK = scored.take(ACTIVATION_K)
            val picked = weightedRandom(topK)
            log("[picked] ${picked.role} (weighted random from top-$ACTIVATION_K)")

            // ── ⑤ 统一 LLM 调用 ──────────────────────────────────
            try {
                val response = picked.respond(_board.value, ::quickLlm)
                log("[${picked.role}] speak=${response.wantsToSpeak} vote=${response.voteBull} tags=${response.normalizedTags}")

                if (response.wantsToSpeak && response.content.isNotBlank()) {
                    // 流式输出
                    val streamContent = callLlmStreaming(picked, picked.buildUnifiedPrompt(_board.value))
                    val finalContent = streamContent.ifBlank { response.content }

                    // 🔥 标准化标签
                    val tags = response.normalizedTags.map { it.name }
                    writeToBoard(picked.role, finalContent, tags)

                    // 投票
                    if (response.voteBull != null) {
                        _board.value = _board.value.apply {
                            votes.add(BoardVote(
                                role = picked.role,
                                agree = response.voteBull,
                                round = _board.value.round,
                            ))
                        }.copy()
                        log("[Vote] ${picked.role} = ${if (response.voteBull) "看多" else "看空"}")
                    }

                    // 共识
                    val b = _board.value
                    if (b.hasConsensus() && !b.consensus) {
                        _board.value = b.copy(consensus = true)
                        log("[共识] 看多${(b.bullRatio() * 100).toInt()}% / 看空${(b.bearRatio() * 100).toInt()}%")
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

    // ── 加权随机 ─────────────────────────────────────────────

    /**
     * 🔥 按分数加权随机选一个（不是全随机，也不是最高分）
     * 分数越高被选中概率越大，但低分也有机会
     */
    private fun weightedRandom(scored: List<Pair<Agent, Double>>): Agent {
        if (scored.size == 1) return scored[0].first

        // 把负分拉平到 0，加 0.1 保证每个人都有机会
        val minScore = scored.minOf { it.second }
        val adjusted = scored.map { it.first to (it.second - minScore + 0.1) }
        val total = adjusted.sumOf { it.second }

        // 加权随机
        var rand = kotlin.random.Random.nextDouble() * total
        for ((agent, weight) in adjusted) {
            rand -= weight
            if (rand <= 0) return agent
        }
        return adjusted.last().first
    }

    // ── Summary Memory ───────────────────────────────────────

    /**
     * 🔥 每 N 轮调用一次 LLM 生成讨论摘要
     * 替代全量 messages，防止长讨论信息丢失
     */
    private suspend fun updateSummary() {
        val board = _board.value
        if (board.messages.size < 3) return  // 消息太少不值得摘要

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

    private suspend fun callLlmStreaming(agent: Agent, prompt: String): String {
        val role = AgentRole.fromId(agent.role)
            ?: return ""
        val config = configProvider()

        _speeches.value = _speeches.value + SpeechRecord(
            agent = role, content = "", summary = "",
            round = _board.value.round, isStreaming = true,
        )

        return try {
            val ctx = MicContext(
                traceId = "r_${System.currentTimeMillis()}",
                causedBy = "runtime",
                round = _board.value.round,
                phase = MeetingState.IDLE,
                subject = _board.value.subject,
                agentRole = role,
                task = agent.role,
            )

            val fullContent = StringBuilder()
            agentPool.callAgentStreaming(role, ctx, systemPromptOverride = prompt).collect { delta ->
                fullContent.append(delta)
                _speeches.value = _speeches.value.dropLast(1) + SpeechRecord(
                    agent = role,
                    content = fullContent.toString(),
                    summary = "",
                    round = _board.value.round,
                    isStreaming = true,
                )
            }

            val result = fullContent.toString()
            _speeches.value = _speeches.value.dropLast(1) + SpeechRecord(
                agent = role, content = result, summary = "",
                round = _board.value.round, isStreaming = false,
            )
            persistSpeech(_speeches.value.last())
            result
        } catch (e: Exception) {
            log("[LLM错误] ${agent.role}: ${e.message}")
            ""
        }
    }

    // ── Board 操作 ────────────────────────────────────────────

    private fun writeToBoard(role: String, content: String, rawTags: List<String>) {
        val b = _board.value
        b.messages.add(BoardMessage(
            role = role, content = content,
            round = b.round, rawTags = rawTags,
        ))
        _board.value = b.copy()
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
            val b = _board.value
            _board.value = b.copy(round = b.round + 1)
        }
    }

    private suspend fun doRating(): String? {
        val prompt = supervisor.buildRatingPrompt(_board.value)
        val content = quickLlm(prompt)
        return parseRating(content)
    }

    private fun finishWithRating(rating: String) {
        val b = _board.value
        _board.value = b.copy(
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
