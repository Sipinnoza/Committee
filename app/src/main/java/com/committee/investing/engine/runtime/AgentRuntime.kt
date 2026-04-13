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
 *  AgentRuntime — Agent 驱动的调度引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心循环（完全 Agent 驱动）：
 *
 *    while (!supervisor说结束):
 *      1. 并行问所有 eligible Agent："你想发言吗？"（LLM）
 *      2. 从说 YES 的 Agent 里随机选一个
 *      3. Agent 执行 act() → LLM 生成发言
 *      4. Agent 投票（LLM：看多/看空）
 *      5. 检查共识（投票比例 > 70%）
 *      6. 更新 Phase（从状态推断）
 *      7. 问 Supervisor："可以结束了吗？"（LLM）
 *
 *  没有 PRIORITY，没有固定顺序，没有字符串匹配共识
 *
 *  安全阀（仅防止无限循环，不控制流程）：
 *    - round > 20：强制结束
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
        private const val ABSOLUTE_MAX_ROUNDS = 20  // 绝对安全阀
        private const val CONSENSUS_THRESHOLD = 0.7f
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
        log("[取消] 会议已终止")
    }

    fun confirmExecution() {
        log("[确认] 用户确认执行")
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

            // 绝对安全阀
            if (board.round > ABSOLUTE_MAX_ROUNDS) {
                log("[安全阀] 超过 ${ABSOLUTE_MAX_ROUNDS} 轮，强制结束")
                finishWithRating("Hold")
                break
            }

            log("[Round ${board.round}] ────────────────────────────")

            // ── 步骤 1：Supervisor 先评估 ──────────────────────

            // 问 Supervisor：讨论是否可以结束了？
            val shouldFinish = supervisor.shouldFinish(board, ::quickLlm)
            log("[Supervisor] shouldFinish=$shouldFinish")

            if (shouldFinish) {
                log("[Supervisor] 判断讨论充分，进入评级")
                val rating = doRating()
                if (rating != null) {
                    finishWithRating(rating)
                    break
                }
            }

            // Supervisor 做轮间点评
            val supervisorComment = callLlmForAgent(supervisor, supervisor.buildSupervisionPrompt(board))
            if (supervisorComment.isNotBlank()) {
                writeToBoard(supervisor.role, supervisorComment)
            }

            // ── 步骤 2：并行问所有 Agent "你想发言吗？" ──────────

            val eligible = agents.filter { it.eligible(_board.value) }
            log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                log("[eligible] 无人有资格，本轮结束")
                advanceRound()
                continue
            }

            // 🔥 并行调用 shouldAct（每个 Agent 各自问 LLM）
            val candidates = findCandidates(eligible)
            log("[candidates] ${candidates.map { it.role }}")

            if (candidates.isEmpty()) {
                log("[candidates] 无人想发言，本轮结束")
                advanceRound()
                continue
            }

            // ── 步骤 3：随机选一个 Agent ──────────────────────

            // 🔥 不是 PRIORITY 排序，是随机
            val chosen = candidates.random()
            log("[调度] 随机选中 → ${chosen.role} (${chosen.displayName})")

            // ── 步骤 4：Agent 执行 ────────────────────────────

            val decision = chosen.act(_board.value)
            if (decision.needsLlm && decision.prompt != null) {
                val content = callLlmForAgent(chosen, decision.prompt)
                if (content.isNotBlank()) {
                    writeToBoard(chosen.role, content)

                    // ── 步骤 5：Agent 投票 ────────────────────
                    val vote = collectVote(chosen)
                    if (vote != null) {
                        _board.value = _board.value.apply {
                            votes.add(vote)
                        }.copy()
                        log("[Vote] ${chosen.role} = ${if (vote.agree) "看多" else "看空"}")
                    }

                    // ── 步骤 6：检查共识 ─────────────────────
                    val b = _board.value
                    if (b.hasConsensus()) {
                        _board.value = b.copy(consensus = true)
                        log("[共识] 投票比例看多${(b.bullRatio() * 100).toInt()}% / 看空${(b.bearRatio() * 100).toInt()}%")
                    }
                }
            }

            // ── 步骤 7：更新 Phase（从状态推断） ──────────────
            updatePhase()

            // 进入下一轮
            advanceRound()
        }
    }

    // ── 并行 shouldAct ────────────────────────────────────────

    /**
     * 并行问所有 eligible Agent "你想发言吗？"
     * 返回说 YES 的 Agent 列表
     */
    private suspend fun findCandidates(eligible: List<Agent>): List<Agent> = coroutineScope {
        eligible.map { agent ->
            async {
                try {
                    agent to agent.shouldAct(_board.value, ::quickLlm)
                } catch (e: Exception) {
                    log("[shouldAct错误] ${agent.role}: ${e.message}")
                    agent to false
                }
            }
        }.awaitAll()
            .filter { (_, wantsToSpeak) -> wantsToSpeak }
            .map { (agent, _) -> agent }
    }

    // ── 投票 ─────────────────────────────────────────────────

    /**
     * 让 Agent 投票（看多/看空）
     * 🔥 不是字符串匹配，是结构化投票
     */
    private suspend fun collectVote(agent: Agent): BoardVote? {
        if (agent is SupervisorAgent) return null  // Supervisor 不投票
        if (agent.role == "executor") return null    // Executor 不投票

        val votePrompt = supervisor.buildVotePrompt(_board.value)
        return try {
            val response = quickLlm(votePrompt)
            val isBull = response.trim().uppercase().let { r ->
                r.startsWith("A") || r.contains("BULL") || r.contains("看多")
            }
            BoardVote(
                role = agent.role,
                agree = isBull,
                reason = "",
                round = _board.value.round,
            )
        } catch (e: Exception) {
            log("[投票错误] ${agent.role}: ${e.message}")
            null
        }
    }

    // ── 评级 ─────────────────────────────────────────────────

    private suspend fun doRating(): String? {
        val prompt = supervisor.buildRatingPrompt(_board.value)
        val content = callLlmForAgent(supervisor, prompt)
        return parseRating(content)
    }

    // ── LLM 调用 ─────────────────────────────────────────────

    /**
     * 轻量 LLM 调用（用于 shouldAct / 投票等短回答）
     * 复用 AgentPool 的流式接口，收集完整响应
     */
    private suspend fun quickLlm(prompt: String): String {
        val sb = StringBuilder()
        val role = AgentRole.SUPERVISOR
        val ctx = MicContext(
            traceId = "quick_${System.currentTimeMillis()}",
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

    /**
     * Agent 正式发言（流式输出，UI 实时更新）
     */
    private suspend fun callLlmForAgent(agent: Agent, prompt: String): String {
        val role = AgentRole.fromId(agent.role)
            ?: return "[错误: 未知角色 ${agent.role}]"
        val config = configProvider()

        log("[LLM] ${agent.role} (${config.provider.displayName}/${config.model})")
        _speeches.value = _speeches.value + SpeechRecord(
            agent = role,
            content = "",
            summary = "",
            round = _board.value.round,
            isStreaming = true,
        )

        return try {
            val ctx = MicContext(
                traceId = "runtime_${System.currentTimeMillis()}",
                causedBy = "supervisor",
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
                agent = role,
                content = result,
                summary = "",
                round = _board.value.round,
                isStreaming = false,
            )
            persistSpeech(_speeches.value.last())
            result
        } catch (e: Exception) {
            val errMsg = "${e.javaClass.simpleName}: ${e.message}"
            log("[LLM错误] ${agent.role}: $errMsg")
            "[调用失败: $errMsg]"
        }
    }

    // ── Board 操作 ────────────────────────────────────────────

    private fun writeToBoard(role: String, content: String) {
        val b = _board.value
        b.messages.add(BoardMessage(role = role, content = content, round = b.round))
        _board.value = b.copy()
        log("[Board] +$role: ${content.take(60)}...")
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

    private fun finishWithRating(rating: String) {
        val b = _board.value
        _board.value = b.copy(
            finished = true,
            finalRating = rating,
            phase = BoardPhase.RATING,
        )
        log("[评级] 最终评级: $rating")

        // 如果有 executor，让他制定执行计划
        val executor = agents.find { it.role == "executor" }
        if (executor != null && _board.value.executionPlan == null) {
            _board.value = _board.value.copy(
                phase = BoardPhase.EXECUTION,
            )
        }

        updateSessionFinished()
    }

    // ── 工具方法 ──────────────────────────────────────────────

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
