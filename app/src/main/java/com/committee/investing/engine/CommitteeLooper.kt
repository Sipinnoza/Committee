package com.committee.investing.engine

import android.util.Log
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.repository.EventRepository
import com.committee.investing.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 投委会核心 Looper
 * 规格文档 §3 — 借鉴 Android Looper/Handler 模式
 *
 * - EventQueue  = Channel<CommitteeEvent>  (类比 MessageQueue)
 * - Looper.loop = processLoop()           (类比 Looper.loop())
 * - Handler     = Scheduler               (确定性调度)
 * - Runnable    = AgentPool.callAgent()   (Agent 任务)
 */
@Singleton
class CommitteeLooper @Inject constructor(
    private val eventRepository: EventRepository,
    private val agentPool: AgentPool,
) {
    // ── 组件 ───────────────────────────────────────────────────────────────
    private val stateEngine = StateEngine()
    private val scheduler = Scheduler()
    private val idempotency = IdempotencyGuard()

    private val _looperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var timerRegistry: TimerRegistry

    private val eventQueue = Channel<CommitteeEvent>(capacity = Channel.UNLIMITED)

    // ── 对外可观察流 ────────────────────────────────────────────────────────
    val currentState: StateFlow<MeetingState> = stateEngine.currentState

    private val _speeches = MutableStateFlow<List<SpeechRecord>>(emptyList())
    val speeches: StateFlow<List<SpeechRecord>> = _speeches.asStateFlow()

    private val _looperLog = MutableSharedFlow<String>(replay = 50)
    val looperLog: SharedFlow<String> = _looperLog.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var currentTraceId: String = ""
    private var currentSubject: String = ""

    // ── 启动 / 停止 ─────────────────────────────────────────────────────────

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        timerRegistry = TimerRegistry(_looperScope)
        _looperScope.launch { processLoop() }
        log("CommitteeLooper 已启动")
    }

    fun stop() {
        _isRunning.value = false
        timerRegistry.cancelAll()
        log("CommitteeLooper 已停止")
    }

    // ── 发起会议 ────────────────────────────────────────────────────────────

    suspend fun requestMeeting(subject: String, configOverride: Map<String, Any> = emptyMap()) {
        // 重置所有状态，确保干净开局
        scheduler.reset()
        stateEngine.reset()
        _speeches.value = emptyList()

        currentTraceId = "MTG-${java.time.LocalDate.now()}-${UUID.randomUUID().toString().take(6).uppercase()}"
        currentSubject = subject
        log("[发起] 新会议: $subject | ID: $currentTraceId")

        // 持久化 Session
        eventRepository.upsertSession(
            MeetingSessionEntity(
                traceId = currentTraceId,
                subject = subject,
                startTime = Instant.now().toEpochMilli(),
                currentState = MeetingState.IDLE.name,
            )
        )

        emit(
            eventType = "meeting_requested",
            agent = "system",
            payload = mapOf("subject" to subject) + configOverride,
        )
    }

    suspend fun confirmExecution() {
        emit(eventType = "execution_confirmed", agent = "user")
    }

    suspend fun cancelMeeting() {
        emit(eventType = "meeting_cancelled", agent = "user")
        scheduler.reset()
        _speeches.value = emptyList()
    }

    // ── 事件发射 ─────────────────────────────────────────────────────────────

    fun emit(
        eventType: String,
        agent: String,
        causedBy: String? = null,
        payload: Map<String, Any> = emptyMap(),
    ) {
        val event = CommitteeEvent(
            event = eventType,
            agent = agent,
            traceId = currentTraceId,
            causedBy = causedBy,
            payload = payload,
        )
        _looperScope.launch { eventQueue.send(event) }
    }

    // ── 主循环 ───────────────────────────────────────────────────────────────

    private suspend fun processLoop() {
        for (event in eventQueue) {
            processEvent(event)
        }
    }

    private suspend fun processEvent(event: CommitteeEvent) {
        Log.e("Looper", "━━━ processEvent: ${event.event} | agent=${event.agent} | state=${stateEngine.state.name} | id=${event.eventId}")

        // 幂等性检查
        if (!idempotency.tryProcess(event.eventId)) {
            Log.e("Looper", "  ⤳ 幂等跳过 ${event.eventId}")
            log("[幂等跳过] ${event.event} (${event.eventId})")
            return
        }

        // 持久化事件（唯一真相源）
        eventRepository.appendEvent(event)

        log("[事件] ${event.event} | Agent: ${event.agent} | 状态: ${stateEngine.state.displayName}")

        // 路径 A：尝试状态转换
        val transition = stateEngine.apply(event)
        if (transition != null) {
            log("[状态转换] ${transition.from.displayName} → ${transition.to.displayName}")
            onStateTransition(transition, event)
            return
        }

        // 路径 B：非转换事件，推进调度器
        val state = stateEngine.state
        if (event.event in NON_TRANSITION_EVENTS) {
            Log.e("Looper", "  ⤳ 非转换事件，进入调度器")
            val actions = scheduler.next(state, event)
            executeActions(actions, event)
        } else {
            Log.e("Looper", "  ⤳ 事件 ${event.event} 既不触发转换也不在 NON_TRANSITION_EVENTS 中，忽略")
        }
    }

    // ── 状态转换后处理 ───────────────────────────────────────────────────────

    private suspend fun onStateTransition(result: TransitionResult, triggerEvent: CommitteeEvent) {
        Log.e("Looper", "  ⤳ onStateTransition: ${result.from.name} → ${result.to.name}")

        // 取消旧定时器
        timerRegistry.cancel("${result.from.name}_timeout")
        timerRegistry.cancel("${result.from.name}_barrier_timeout")
        timerRegistry.cancel("${result.from.name}_speech_timeout")

        // emit state_changed
        emit(eventType = "state_changed", agent = "runtime",
            causedBy = triggerEvent.eventId,
            payload = mapOf("from" to result.from.name, "to" to result.to.name))

        // 保存快照
        eventRepository.updateSessionState(currentTraceId, result.to, buildSnapshot(result.to))

        // Save speeches to DB when meeting completes
        if (result.to == MeetingState.COMPLETED || result.to == MeetingState.IDLE) {
            runCatching {
                eventRepository.saveSpeeches(currentTraceId, _speeches.value)
            }.onFailure { e ->
                Log.e("Looper", "[saveSpeeches] failed: ${e.message}")
            }
        }

        // 向调度器请求下一批 Action
        val actions = scheduler.next(result.to, triggerEvent)
        Log.e("Looper", "  ⤳ onStateTransition scheduler 返回 ${actions.size} 个 Action")
        executeActions(actions, triggerEvent)
    }

    // ── 执行 Action ──────────────────────────────────────────────────────────

    private suspend fun executeActions(actions: List<SchedulerAction>, triggerEvent: CommitteeEvent) {
        if (actions.isEmpty()) {
            Log.e("Looper", "[executeActions] 空 Action 列表")
            log("[调度] 无待执行 Action，等待下一事件")
        }
        for (action in actions) {
            when (action) {
                is SchedulerAction.SendMic -> {
                    log("[话筒] → ${action.agent.displayName} | 任务: ${action.task}")
                    timerRegistry.schedule("${action.phase.name}_speech_timeout", action.phase.speechTimeoutMs) {
                        log("[超时] ${action.agent.displayName} 未响应，发出 agent_timeout")
                        emit("agent_timeout", action.agent.id, action.causedBy,
                            mapOf("phase" to action.phase.name))
                    }
                    // 在后台调用 LLM Agent
                    _looperScope.launch {
                        runCatching {
                            callAgent(action, triggerEvent)
                        }.onFailure { e ->
                            Log.e("Looper", "[Agent 错误] ${action.agent.displayName}: ${e.javaClass.simpleName} - ${e.message}")
                            log("[Agent 错误] ${action.agent.displayName}: ${e.message}")
                            // 移除可能的 streaming 占位
                            _speeches.value = _speeches.value.filter { !(it.isStreaming && it.agent == action.agent) }
                            // 发 speech_complete 让流程继续，不要卡死
                            emit("speech_complete", action.agent.id, action.causedBy,
                                mapOf("error" to (e.message ?: "unknown")))
                        }
                    }
                }

                is SchedulerAction.EmitEvent -> {
                    log("[调度] 发射事件: ${action.eventType}")
                    emit(action.eventType, "scheduler", action.causedBy, action.payload)
                }

                is SchedulerAction.SetTimer -> {
                    timerRegistry.schedule(action.timerId, action.delayMs) {
                        log("[定时器触发] ${action.timerId}")
                    }
                }

                is SchedulerAction.CancelTimer -> {
                    timerRegistry.cancel(action.timerId)
                }
            }
        }
    }

    private suspend fun callAgent(action: SchedulerAction.SendMic, triggerEvent: CommitteeEvent) {
        log("[思考中] ${action.agent.displayName} 正在思考... (任务: ${action.task})")

        val opponentSummary = _speeches.value
            .filter { it.agent != action.agent }
            .lastOrNull()?.summary ?: ""

        // 从 task 字段解析 round，如 "debate_round_3"
        val roundFromTask = Regex("round_(\\d+)").find(action.task)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val ctx = MicContext(
            traceId = action.traceId,
            causedBy = action.causedBy,
            round = roundFromTask,
            phase = action.phase,
            subject = currentSubject,
            opponentSummary = "",
            previousSpeeches = _speeches.value,
            agentRole = action.agent,
            task = action.task,
        )

        // 创建一个 streaming 占位 SpeechRecord
        val speechId = "sp_${java.util.UUID.randomUUID().toString().replace("-", "").take(10)}"
        val placeholder = SpeechRecord(
            id = speechId,
            agent = action.agent,
            round = 1,
            summary = "",
            content = "",
            isStreaming = true,
        )
        _speeches.value = _speeches.value + placeholder

        val startTime = System.currentTimeMillis()
        var fullContent = ""

        try {
            agentPool.callAgentStreaming(action.agent, ctx).collect { delta ->
                fullContent += delta
                // 实时更新最后一条发言
                _speeches.value = _speeches.value.map {
                    if (it.id == speechId) it.copy(content = fullContent) else it
                }
            }
            Log.e("Looper", "[callAgent] ${action.agent.id} collect 完成，共 ${fullContent.length} 字符")
        } catch (e: Exception) {
            Log.e("Looper", "[callAgent] ${action.agent.id} 异常: ${e.javaClass.simpleName} - ${e.message}")
            log("[API 错误] ${action.agent.displayName}: ${e.javaClass.simpleName} - ${e.message}")
            // 移除失败的占位
            _speeches.value = _speeches.value.filter { it.id != speechId }
            throw e
        }

        val elapsed = System.currentTimeMillis() - startTime
        val summary = fullContent.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: fullContent.take(100)
        val hasConsensus = fullContent.contains("【CONSENSUS REACHED】")
        val rating = Regex("【最终评级】(Buy|Overweight|Hold\\+|Hold|Underweight|Sell)")
            .find(fullContent)?.groupValues?.get(1)

        // 标记完成
        _speeches.value = _speeches.value.map {
            if (it.id == speechId) it.copy(
                summary = summary,
                isStreaming = false,
            ) else it
        }

        log("[响应] ${action.agent.displayName} 回复完成 (${elapsed}ms, ${fullContent.length}字)")

        // 取消超时定时器
        timerRegistry.cancel("${action.phase.name}_speech_timeout")

        // 发出完成事件 — 根据 task 类型决定事件名
        val eventType = when (action.task) {
            "prepare" -> if (action.agent == AgentRole.INTEL) "intel_baseline_ready" else "agent_ready"
            "validate_entry" -> {
                val passed = !fullContent.contains("拒绝") && !fullContent.contains("不通过")
                if (passed) "validation_passed" else "validation_rejected"
            }
            "adjudicate" -> "adjudication_complete"
            "publish_rating" -> "rating_approved"
            "write_minutes" -> "minutes_published"
            "plan_finalized" -> "plan_finalized"
            else -> {
                // debate / assessment 轮次：检查共识
                if (hasConsensus) "consensus_reached" else "speech_complete"
            }
        }
        Log.e("Looper", "[callAgent] ${action.agent.id} 完成，发射事件: $eventType | consensus=$hasConsensus rating=$rating")

        emit(
            eventType = eventType,
            agent = action.agent.id,
            causedBy = action.causedBy,
            payload = buildMap {
                put("summary", summary)
                rating?.let { put("rating", it) }
                if (hasConsensus) put("consensus", true)
            }
        )
    }

    // ── Recovery（规格文档 §8.3）────────────────────────────────────────────

    suspend fun recover(traceId: String) {
        log("[恢复] 从事件流重放状态 traceId=$traceId")
        currentTraceId = traceId

        // 重建幂等集
        val ids = eventRepository.getProcessedEventIds(traceId)
        idempotency.restore(ids)

        // 从事件流重放 FSM
        val replayedState = eventRepository.replayState(traceId)
        stateEngine.restore(replayedState)
        log("[恢复] 重放状态: ${replayedState.displayName}")

        // 重建发言记录
        val events = eventRepository.getEventsByTrace(traceId)
        val speeches = events.filter { it.event == "speech_complete" || it.event == "consensus_reached" }
            .mapNotNull { evt ->
                val role = AgentRole.fromId(evt.agent) ?: return@mapNotNull null
                SpeechRecord(
                    agent = role,
                    round = (evt.payload["round"] as? Double)?.toInt() ?: 0,
                    summary = evt.payload["summary"] as? String ?: "",
                    content = evt.payload["summary"] as? String ?: "",
                    timestamp = evt.ts,
                )
            }
        _speeches.value = speeches
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        _looperScope.launch { _looperLog.emit("[${java.time.LocalTime.now()}] $msg") }
    }

    private fun buildSnapshot(state: MeetingState): Map<String, Any> = mapOf(
        "state" to state.name,
        "traceId" to currentTraceId,
        "lastSaved" to Instant.now().toString(),
    )

    companion object {
        private val NON_TRANSITION_EVENTS = setOf(
            "speech_complete", "agent_ready", "intel_baseline_ready", "intel_degraded",
            "agent_timeout", "alert_fired",
        )

        private val MeetingState.speechTimeoutMs: Long get() = when (this) {
            MeetingState.VALIDATING           -> 120_000L
            MeetingState.PHASE1_ADJUDICATING  -> 120_000L
            MeetingState.FINAL_RATING         -> 60_000L
            MeetingState.COMPLETED            -> 120_000L
            else                              -> 600_000L
        }
    }
}
