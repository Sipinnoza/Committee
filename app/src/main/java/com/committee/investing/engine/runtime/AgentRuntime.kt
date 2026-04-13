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
 *  AgentRuntime — 优化版
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  三刀优化：
 *    1. 稀疏激活：每轮只问 k=2 个 Agent（而不是全部）
 *    2. 合并调用：shouldAct + act + vote → 单次 LLM → UnifiedResponse
 *    3. Supervisor 降频：结束判断每 2 轮或共识后才问，点评只在有分歧时
 *
 *  LLM 调用量估算（10 轮会议）：
 *    旧：~50 次（每轮 N+3）
 *    新：~20 次（每轮 1 supervisor + 1 agent unified）
 *    ↓ 60%
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
        private const val ACTIVATION_K = 2       // 🔥 每轮最多激活 2 个 Agent
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
        if (_isRunning.value) {
            log("[startMeeting] 已有会议在运行")
            return
        }
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

            // ── 步骤 1：Supervisor 结束判断（🔥 降频：每 2 轮 或 共识后） ──
            if (board.round % 2 == 0 || board.consensus || board.round >= 4) {
                val shouldFinish = askSupervisorFinish(board)
                log("[Supervisor] shouldFinish=$shouldFinish")
                if (shouldFinish) {
                    val rating = doRating()
                    finishWithRating(rating ?: "Hold")
                    break
                }
            }

            // ── 步骤 2：Supervisor 点评（🔥 降频：只在有分歧或无发言时） ──
            if (shouldSupervisorComment(board)) {
                val comment = quickLlm(supervisor.buildSupervisionPrompt(board))
                if (comment.isNotBlank() && comment.length > 10) {
                    writeToBoard(supervisor.role, comment, emptyList())
                }
            }

            // ── 步骤 3：🔥 稀疏激活 — 只选 k 个 Agent ──────────────
            val eligible = agents.filter { it.eligible(_board.value) }
            log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                log("[eligible] 无人有资格，本轮跳过")
                advanceRound()
                continue
            }

            // 🔥 从 eligible 里随机选 k 个（稀疏激活）
            val picked = eligible.shuffled().take(ACTIVATION_K)
            log("[picked] ${picked.map { it.role }}")

            // ── 步骤 4：🔥 统一 LLM 调用（respond = shouldAct + act + vote） ──
            for (agent in picked) {
                if (_board.value.finished) break

                try {
                    val response = agent.respond(_board.value, ::quickLlm)
                    log("[${agent.role}] speak=${response.wantsToSpeak} vote=${response.voteBull} tags=${response.tags}")

                    if (response.wantsToSpeak && response.content.isNotBlank()) {
                        // 流式输出（用户看到打字效果）
                        val streamContent = callLlmStreaming(agent, agent.buildUnifiedPrompt(_board.value))
                        if (streamContent.isNotBlank()) {
                            writeToBoard(agent.role, streamContent, response.tags)
                        } else {
                            // 流式失败时用 unified response 的 content
                            writeToBoard(agent.role, response.content, response.tags)
                        }

                        // 🔥 投票（从 unified response 直接取，不需要额外 LLM）
                        if (response.voteBull != null) {
                            _board.value = _board.value.apply {
                                votes.add(BoardVote(
                                    role = agent.role,
                                    agree = response.voteBull,
                                    round = _board.value.round,
                                ))
                            }.copy()
                            log("[Vote] ${agent.role} = ${if (response.voteBull) "看多" else "看空"}")
                        }

                        // 检查共识
                        val b = _board.value
                        if (b.hasConsensus() && !b.consensus) {
                            _board.value = b.copy(consensus = true)
                            log("[共识] 看多${(b.bullRatio() * 100).toInt()}% / 看空${(b.bearRatio() * 100).toInt()}%")
                        }

                        updatePhase()
                    } else {
                        log("[${agent.role}] SPEAK=NO，跳过")
                    }
                } catch (e: Exception) {
                    log("[${agent.role}] 错误: ${e.message}")
                }
            }

            advanceRound()
        }
    }

    // ── Supervisor 降频逻辑 ────────────────────────────────────

    /** 是否需要 Supervisor 点评（降频） */
    private fun shouldSupervisorComment(board: Blackboard): Boolean {
        // 第 1 轮：不需要（还没东西可评）
        if (board.round <= 1) return false
        // 本轮已有 supervisor 发言
        if (board.messages.any { it.role == "supervisor" && it.round == board.round }) return false
        // 有共识了：不需要点评
        if (board.consensus) return false
        // 至少有 3 条非 supervisor 消息才值得点评
        val nonSupervisorMsgs = board.messages.count { it.role != "supervisor" }
        return nonSupervisorMsgs >= 3
    }

    /** 问 Supervisor 能不能结束（降频后仍然问） */
    private suspend fun askSupervisorFinish(board: Blackboard): Boolean {
        val prompt = supervisor.buildFinishPrompt(board)
        val response = quickLlm(prompt)
        val r = response.trim().uppercase()
        return r.startsWith("YES") || r.startsWith("Y") || r.startsWith("是")
    }

    // ── LLM 调用 ─────────────────────────────────────────────

    /** 轻量 LLM（YES/NO 类短回答） */
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

    /** Agent 正式发言（流式，UI 实时显示） */
    private suspend fun callLlmStreaming(agent: Agent, prompt: String): String {
        val role = AgentRole.fromId(agent.role)
            ?: return "[错误: 未知角色 ${agent.role}]"

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

    private fun writeToBoard(role: String, content: String, tags: List<String>) {
        val b = _board.value
        b.messages.add(BoardMessage(
            role = role, content = content,
            round = b.round, tags = tags,
        ))
        _board.value = b.copy()
        log("[Board] +$role tags=$tags: ${content.take(60)}...")
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

        // 给 Executor 机会
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
