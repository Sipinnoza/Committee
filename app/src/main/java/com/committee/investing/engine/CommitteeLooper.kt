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
 *
 * 修复：
 * 1. emit 改为 private suspend fun + eventQueue.send()，保证事件严格入队顺序。
 *    原代码用 _looperScope.launch { send() }，多次 emit 时各协程调度顺序不确定，
 *    可能导致 speech_complete 先于 agent_ready 到达，破坏屏障逻辑。
 *
 * 2. _speeches 读-改-写改为 StateFlow.update()（CAS 原子操作）。
 *    原代码 `_speeches.value = _speeches.value.map{...}` 在 PREPPING 并行阶段
 *    多个 agent 协程并发修改时存在竞态，一个协程的写入会覆盖另一个。
 *
 * 3. validate_entry 改为检测 【PASS】/【REJECT】 结构化标记。
 *    原代码 contains("拒绝") 会在策略师反驳空头理由时误判为 rejected。
 *
 * 4. 发言完成后立即增量持久化到 Room，不再等待 COMPLETED 才一次性存库。
 *    原代码在 App 崩溃或用户取消时发言全部丢失。
 *
 * 5. recover() 从 speechDao 加载完整发言内容，原代码从事件 payload 重建，
 *    内容只有第一行摘要（payload["summary"]），不是完整发言。
 *
 * 6. cancelMeeting 取消后 _speeches 清空，requestMeeting 重置前也清空。
 */
@Singleton
class CommitteeLooper @Inject constructor(
    private val eventRepository: EventRepository,
    private val agentPool: AgentPool,
    private val stateEngine: StateEngine,
    private val scheduler: Scheduler,
) {
    private val idempotency  = IdempotencyGuard()

    private val _looperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var timerRegistry: TimerRegistry

    private val eventQueue = Channel<CommitteeEvent>(capacity = Channel.UNLIMITED)

    // ── 对外可观察流 ────────────────────────────────────────────────────────

    val currentState: StateFlow<MeetingState> = stateEngine.currentState

    private val _speeches    = MutableStateFlow<List<SpeechRecord>>(emptyList())
    val speeches: StateFlow<List<SpeechRecord>> = _speeches.asStateFlow()

    private val _looperLog   = MutableSharedFlow<String>(replay = 50)
    val looperLog: SharedFlow<String> = _looperLog.asSharedFlow()

    private val _isRunning   = MutableStateFlow(false)
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

    // ── 公开接口 ────────────────────────────────────────────────────────────

    suspend fun requestMeeting(subject: String, configOverride: Map<String, Any> = emptyMap()) {
        scheduler.reset()
        stateEngine.reset()
        _speeches.value = emptyList()

        currentTraceId = "MTG-${java.time.LocalDate.now()}-${UUID.randomUUID().toString().take(6).uppercase()}"
        currentSubject = subject
        log("[发起] 新会议: $subject | ID: $currentTraceId")

        eventRepository.upsertSession(
            MeetingSessionEntity(
                traceId      = currentTraceId,
                subject      = subject,
                startTime    = Instant.now().toEpochMilli(),
                currentState = MeetingState.IDLE.name,
            )
        )

        emit(
            eventType = "meeting_requested",
            agent     = "system",
            payload   = mapOf("subject" to subject) + configOverride,
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

    // ── 事件发射（修复：private suspend，直接 send 保证顺序）──────────────────

    /**
     * 修复：原代码 `_looperScope.launch { eventQueue.send(event) }` 为每次 emit 创建独立协程，
     * 多次调用时协程调度顺序不确定，可能导致事件乱序。
     * 改为 suspend fun + 直接 send()。eventQueue 容量 UNLIMITED，send 不会真正挂起。
     */
    private suspend fun emit(
        eventType: String,
        agent: String,
        causedBy: String? = null,
        payload: Map<String, Any> = emptyMap(),
    ) {
        val event = CommitteeEvent(
            event    = eventType,
            agent    = agent,
            traceId  = currentTraceId,
            causedBy = causedBy,
            payload  = payload,
        )
        eventQueue.send(event)
    }

    // ── 主循环 ───────────────────────────────────────────────────────────────

    private suspend fun processLoop() {
        for (event in eventQueue) {
            processEvent(event)
        }
    }

    private suspend fun processEvent(event: CommitteeEvent) {
        Log.e("Looper", "━━━ processEvent: ${event.event} | agent=${event.agent} | state=${stateEngine.state.name}")

        if (!idempotency.tryProcess(event.eventId)) {
            log("[幂等跳过] ${event.event} (${event.eventId})")
            return
        }

        eventRepository.appendEvent(event)
        log("[事件] ${event.event} | Agent: ${event.agent} | 状态: ${stateEngine.state.displayName}")

        val transition = stateEngine.apply(event)
        if (transition != null) {
            log("[状态转换] ${transition.from.displayName} → ${transition.to.displayName}")
            onStateTransition(transition, event)
            return
        }

        val state = stateEngine.state
        if (event.event in NON_TRANSITION_EVENTS) {
            val actions = scheduler.next(state, event)
            executeActions(actions, event)
        } else {
            Log.e("Looper", "  ⤳ 事件 ${event.event} 既不触发转换也不在 NON_TRANSITION_EVENTS 中，忽略")
        }
    }

    // ── 状态转换后处理 ───────────────────────────────────────────────────────

    private suspend fun onStateTransition(result: TransitionResult, triggerEvent: CommitteeEvent) {
        timerRegistry.cancel("${result.from.name}_timeout")
        timerRegistry.cancel("${result.from.name}_barrier_timeout")
        timerRegistry.cancel("${result.from.name}_speech_timeout")

        emit(
            eventType = "state_changed",
            agent     = "runtime",
            causedBy  = triggerEvent.eventId,
            payload   = mapOf("from" to result.from.name, "to" to result.to.name),
        )

        eventRepository.updateSessionState(currentTraceId, result.to, buildSnapshot(result.to))

        // 修复：移除在 COMPLETED/IDLE 的批量 saveSpeeches。
        // 各发言完成后已在 callAgent 末尾增量保存，此处无需再存。
        // （原代码在 IDLE 时存，App 崩溃/取消时发言丢失）

        val actions = scheduler.next(result.to, triggerEvent)
        executeActions(actions, triggerEvent)
    }

    // ── 执行 Action ──────────────────────────────────────────────────────────

    private suspend fun executeActions(actions: List<SchedulerAction>, triggerEvent: CommitteeEvent) {
        if (actions.isEmpty()) {
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
                    _looperScope.launch {
                        runCatching {
                            callAgent(action, triggerEvent)
                        }.onFailure { e ->
                            Log.e("Looper", "[Agent 错误] ${action.agent.displayName}: ${e.message}")
                            log("[Agent 错误] ${action.agent.displayName}: ${e.message}")
                            // 修复：移除流式占位（用 update 原子操作，避免并发覆盖）
                            _speeches.update { list ->
                                list.filter { !(it.isStreaming && it.agent == action.agent) }
                            }
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

    // ── Agent 调用 ───────────────────────────────────────────────────────────

    private suspend fun callAgent(action: SchedulerAction.SendMic, triggerEvent: CommitteeEvent) {
        log("[思考中] ${action.agent.displayName} 正在思考... (任务: ${action.task})")

        val roundFromTask = Regex("round_(\\d+)").find(action.task)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val ctx = MicContext(
            traceId          = action.traceId,
            causedBy         = action.causedBy,
            round            = roundFromTask,
            phase            = action.phase,
            subject          = currentSubject,
            opponentSummary  = "",
            previousSpeeches = _speeches.value,
            agentRole        = action.agent,
            task             = action.task,
        )

        val speechId    = "sp_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val placeholder = SpeechRecord(
            id         = speechId,
            agent      = action.agent,
            round      = roundFromTask,
            summary    = "",
            content    = "",
            isStreaming = true,
        )

        // 修复：用 update() 原子追加，避免并行 agent 相互覆盖
        _speeches.update { it + placeholder }

        val startTime   = System.currentTimeMillis()
        var fullContent = ""

        try {
            agentPool.callAgentStreaming(action.agent, ctx).collect { delta ->
                fullContent += delta
                // 修复：用 update() 原子更新，避免 read-modify-write 竞态
                _speeches.update { list ->
                    list.map { if (it.id == speechId) it.copy(content = fullContent) else it }
                }
            }
        } catch (e: Exception) {
            Log.e("Looper", "[callAgent] ${action.agent.id} 异常: ${e.javaClass.simpleName} - ${e.message}")
            log("[API 错误] ${action.agent.displayName}: ${e.javaClass.simpleName} - ${e.message}")
            _speeches.update { list -> list.filter { it.id != speechId } }
            throw e
        }

        val elapsed      = System.currentTimeMillis() - startTime
        val summary      = fullContent.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: fullContent.take(100)
        val hasConsensus = fullContent.contains("【CONSENSUS REACHED】")
        val rating       = Regex("【最终评级】(Buy|Overweight|Hold\\+|Hold|Underweight|Sell)")
            .find(fullContent)?.groupValues?.get(1)

        // 标记流式结束
        _speeches.update { list ->
            list.map { if (it.id == speechId) it.copy(summary = summary, isStreaming = false) else it }
        }

        log("[响应] ${action.agent.displayName} 回复完成 (${elapsed}ms, ${fullContent.length}字)")

        // 修复：发言完成后立即增量持久化，不再等 COMPLETED 才批量存
        // 原代码仅在状态转入 COMPLETED/IDLE 时保存，崩溃或取消时数据丢失
        val completedSpeech = _speeches.value.find { it.id == speechId }
        if (completedSpeech != null) {
            runCatching {
                eventRepository.saveSpeeches(currentTraceId, listOf(completedSpeech))
            }.onFailure { e ->
                Log.e("Looper", "[saveSpeeches] 增量保存失败: ${e.message}")
            }
        }

        timerRegistry.cancel("${action.phase.name}_speech_timeout")

        // ── 事件类型路由 ──────────────────────────────────────────────────────
        val eventType = when {
            // validate_entry：修复 — 使用结构化标记，原 contains("拒绝") 在反驳空头时误判
            action.task == "validate_entry" -> {
                if (fullContent.contains("【PASS】")) "validation_passed" else "validation_rejected"
            }
            action.task == "prepare" ->
                if (action.agent == AgentRole.INTEL) "intel_baseline_ready" else "agent_ready"
            action.task == "adjudicate"     -> "adjudication_complete"
            action.task == "publish_rating" -> "rating_approved"
            action.task == "write_minutes"  -> "minutes_published"
            action.task == "plan_finalized" -> "plan_finalized"
            // debate / assess 等轮次任务：共识达成则推进，否则继续轮转
            else -> if (hasConsensus) "consensus_reached" else "speech_complete"
        }

        Log.e("Looper", "[callAgent] ${action.agent.id} 完成，发射事件: $eventType | consensus=$hasConsensus rating=$rating")

        emit(
            eventType = eventType,
            agent     = action.agent.id,
            causedBy  = action.causedBy,
            payload   = buildMap {
                put("summary", summary)
                rating?.let { put("rating", it) }
                if (hasConsensus) put("consensus", true)
            }
        )
    }

    // ── Recovery（规格文档 §8.3）────────────────────────────────────────────

    /**
     * 修复：直接从 speechDao 加载完整发言内容。
     * 原代码从事件 payload 重建发言：payload["summary"] 只是第一行摘要，
     * 恢复后历史记录的 content 字段为空或极短。
     */
    suspend fun recover(traceId: String) {
        log("[恢复] 从事件流重放状态 traceId=$traceId")
        currentTraceId = traceId

        val ids = eventRepository.getProcessedEventIds(traceId)
        idempotency.restore(ids)

        val replayedState = eventRepository.replayState(traceId)
        stateEngine.restore(replayedState)
        log("[恢复] 重放状态: ${replayedState.displayName}")

        // 修复：从 DB 读取完整发言，原代码从 events payload 读 summary 截断字段
        val dbSpeeches = runCatching {
            eventRepository.getSpeechesByTrace(traceId)
        }.getOrDefault(emptyList())
        _speeches.value = dbSpeeches

        log("[恢复] 加载 ${dbSpeeches.size} 条发言记录")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        _looperScope.launch { _looperLog.emit("[${java.time.LocalTime.now()}] $msg") }
    }

    private fun buildSnapshot(state: MeetingState): Map<String, Any> = mapOf(
        "state"     to state.name,
        "traceId"   to currentTraceId,
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