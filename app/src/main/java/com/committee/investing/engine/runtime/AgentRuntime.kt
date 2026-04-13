package com.committee.investing.engine.runtime

import android.util.Log
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.repository.EventRepository
import com.committee.investing.engine.AgentPool
import com.committee.investing.engine.LlmConfig
import com.committee.investing.domain.model.MicContext
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.SpeechRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * AgentRuntime — 替代 CommitteeLooper 的新调度引擎
 *
 * 核心循环：
 *   while (!finished):
 *     1. Supervisor 执行 act()
 *     2. Supervisor 决定下一个 Agent (decideNextAgent)
 *     3. 如果没候选人 → 进入下一轮
 *     4. Agent 执行 act()
 *     5. 如果 needsLlm → 调用 LLM
 *     6. 结果写入 Blackboard
 *     7. 检查共识/结束条件
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
        private const val MAX_GLOBAL_ROUNDS = 10
        private const val MAX_CONSECUTIVE_PASSES = 3
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
        _board.value = Blackboard(subject = subject, phase = BoardPhase.ANALYSIS)
        _speeches.value = emptyList()
        _runtimeLog.value = emptyList()
        log("[启动] traceId=$currentTraceId 标的=$subject agents=${agents.map { it.role }} + supervisor")

        // 持久化 session
        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = currentTraceId,
                subject = subject,
                startTime = System.currentTimeMillis(),
                currentState = "PREPPING",
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
        var consecutivePasses = 0

        while (!_board.value.finished && _board.value.round <= MAX_GLOBAL_ROUNDS) {
            val currentBoard = _board.value
            log("[Round ${currentBoard.round}] ────────────────────────────")

            // 1) Supervisor 先行动
            val supervisorResult = executeAgent(supervisor)
            if (supervisorResult is AgentAction.Finish) {
                log("[Supervisor] 请求结束会议")
                handleSupervisorFinish(supervisorResult)
                break
            }
            if (supervisorResult is AgentAction.Speak && supervisorResult.content.isNotBlank()) {
                writeToBoard(supervisor.role, supervisorResult.content)
            }

            // 2) Supervisor 选择下一个 Agent
            var agent = supervisor.decideNextAgent(agents, _board.value)
            var actedThisRound = false

            while (agent != null && !_board.value.finished) {
                log("[调度] → ${agent.role}")

                val result = executeAgent(agent)
                when (result) {
                    is AgentAction.Speak -> {
                        if (result.content.isNotBlank()) {
                            writeToBoard(agent.role, result.content)
                            actedThisRound = true
                            consecutivePasses = 0
                        }
                    }
                    is AgentAction.Vote -> {
                        val b = _board.value
                        b.votes.add(BoardVote(
                            role = agent.role,
                            agree = result.agree,
                            reason = result.reason,
                            round = b.round,
                        ))
                        _board.value = b.copy()
                        log("[Vote] ${agent.role} = ${if (result.agree) "看多" else "看空"}")
                        actedThisRound = true
                        consecutivePasses = 0
                    }
                    is AgentAction.Pass -> {
                        log("[Pass] ${agent.role} 跳过")
                    }
                    is AgentAction.Finish -> {
                        log("[${agent.role}] 请求结束")
                        consecutivePasses = 0
                        break
                    }
                }

                if (checkConsensus()) {
                    log("[共识] 检测到共识达成！")
                    break
                }

                agent = supervisor.decideNextAgent(agents, _board.value)
            }

            if (!actedThisRound) consecutivePasses++
            if (consecutivePasses >= MAX_CONSECUTIVE_PASSES) {
                log("[安全阀] 连续 $consecutivePasses 轮无行动，强制结束")
                forceFinish()
                break
            }

            if (!_board.value.finished) {
                val newRound = _board.value.round + 1
                _board.value = _board.value.copy(round = newRound)
                log("[Round] 进入第 $newRound 轮")
            }
        }

        _board.value = _board.value.copy(finished = true, phase = BoardPhase.DONE)
        log("[结束] 会议完成，共 ${_board.value.round} 轮，${_board.value.messages.size} 条发言")
        updateSessionFinished()
    }

    // ── Agent 执行 ───────────────────────────────────────────────

    private suspend fun executeAgent(agent: Agent): AgentAction {
        val decision = agent.act(_board.value)

        return when {
            decision.needsLlm && decision.prompt != null -> {
                val content = callLlm(agent, decision.prompt)
                when (decision.action) {
                    is AgentAction.Finish -> {
                        AgentAction.Finish(rating = parseRating(content))
                    }
                    else -> {
                        if (content.contains("共识达成") || content.contains("CONSENSUS")) {
                            _board.value = _board.value.copy(consensus = true)
                        }
                        AgentAction.Speak(content)
                    }
                }
            }
            else -> decision.action
        }
    }

    private suspend fun callLlm(agent: Agent, prompt: String): String {
        val role = AgentRole.entries.find { it.id == agent.role }
            ?: return "[错误: 未知角色 ${agent.role}]"
        val config = configProvider()

        log("[LLM] 调用 ${agent.role} (${config.provider.displayName}/${config.model})")
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
            // 增量保存到 DB
            persistSpeech(_speeches.value.last())
            result
        } catch (e: Exception) {
            val errMsg = "${e.javaClass.simpleName}: ${e.message}"
            log("[LLM错误] ${agent.role}: $errMsg")
            "[调用失败: $errMsg]"
        }
    }

    // ── Blackboard 操作 ──────────────────────────────────────────

    private fun writeToBoard(role: String, content: String) {
        val b = _board.value
        b.messages.add(BoardMessage(role = role, content = content, round = b.round))
        _board.value = b.copy()
    }

    private fun handleSupervisorFinish(action: AgentAction.Finish) {
        val b = _board.value
        val rating = action.rating ?: b.messages
            .lastOrNull { it.role == "supervisor" }?.content?.let { parseRating(it) }
        _board.value = b.copy(
            finished = true,
            finalRating = rating ?: "Hold",
            phase = BoardPhase.RATING,
        )
        log("[评级] 最终评级: ${_board.value.finalRating}")
    }

    private fun forceFinish() {
        _board.value = _board.value.copy(
            finished = true,
            finalRating = "Hold",
            phase = BoardPhase.DONE,
        )
    }

    private fun checkConsensus(): Boolean {
        val b = _board.value
        if (b.consensus) return true
        val lastMsg = b.messages.lastOrNull { it.role == "supervisor" }
        if (lastMsg?.content?.contains("共识达成") == true || lastMsg?.content?.contains("CONSENSUS") == true) {
            _board.value = b.copy(consensus = true)
            return true
        }
        return false
    }

    private fun parseRating(content: String): String? {
        val regex = Regex("""最终评级.*?(Buy|Overweight|Hold\+|Hold|Underweight|Sell)""")
        return regex.find(content)?.groupValues?.getOrNull(1)
    }

    private fun log(msg: String) {
        Log.e(TAG, msg)
        _runtimeLog.value = (_runtimeLog.value + msg).takeLast(200)
    }

    /** 会议结束时更新 DB session */
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
            // 保存所有已完成的 speech
            repository.saveSpeeches(currentTraceId, _speeches.value)
        }
    }

    /** 每次 speech 完成时增量保存 */
    private fun persistSpeech(speech: SpeechRecord) {
        if (currentTraceId.isBlank() || speech.isStreaming) return
        scope.launch {
            repository.saveSpeeches(currentTraceId, listOf(speech))
        }
    }
}
