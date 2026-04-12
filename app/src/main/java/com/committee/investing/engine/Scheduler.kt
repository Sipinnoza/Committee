package com.committee.investing.engine

import com.committee.investing.engine.flow.StateMachine
import com.committee.investing.engine.flow.PhaseDef
import com.committee.investing.engine.flow.Strategy as FlowStrategy
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.CommitteeEvent
import com.committee.investing.domain.model.MeetingState

import android.util.Log

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  确定性调度器 — 数据驱动版
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心变化：
 *    ❌ 旧：PHASE_CONFIG 硬编码 map<MeetingState, PhaseConfig>
 *    ✅ 新：从 StateMachine.flow.phases 查 PhaseDef
 *
 *  Phase 配置来源：
 *    filesDir/flows/default_flow.json → assets/flows/default_flow.json
 *
 *  调度逻辑不变（三种策略），但数据全部来自 DSL
 */
class Scheduler(private val stateMachine: StateMachine) {

    // ── 运行时状态 ────────────────────────────────────────────────────

    private val onceFired   = mutableSetOf<String>()       // state name
    private val barriers    = mutableMapOf<String, BarrierState>()  // state name → barrier
    private val turnIndex   = mutableMapOf<String, Int>()  // state name → agent index
    private val roundCount  = mutableMapOf<String, Int>()  // state name → round number

    // AgentRole 解析缓存：String id → AgentRole enum
    private val roleCache = mutableMapOf<String, AgentRole?>()

    private fun resolveAgent(agentId: String): AgentRole? {
        return roleCache.getOrPut(agentId) { AgentRole.entries.find { it.id == agentId } }
    }

    // ── 公开接口 ─────────────────────────────────────────────────────

    fun next(state: MeetingState, event: CommitteeEvent): List<SchedulerAction> {
        val stateName = state.name
        val phaseDef = stateMachine.phaseDefOf(stateName)
        if (phaseDef == null) {
            Log.e(TAG, "[next] state=$stateName event=${event.event} → 无 phase 配置，返回空")
            return emptyList()
        }
        Log.e(TAG, "[next] state=$stateName event=${event.event} strategy=${phaseDef.strategy} agents=${phaseDef.agents}")

        val actions = when (phaseDef.strategy) {
            FlowStrategy.ONCE        -> scheduleOnce(stateName, phaseDef, event)
            FlowStrategy.PARALLEL    -> scheduleParallel(stateName, phaseDef, event)
            FlowStrategy.ROUND_ROBIN -> scheduleRoundRobin(stateName, phaseDef, event)
        }
        Log.e(TAG, "[next] → ${actions.size} actions: ${actions.map { it.javaClass.simpleName }}")
        return actions
    }

    fun reset() {
        onceFired.clear()
        barriers.clear()
        turnIndex.clear()
        roundCount.clear()
    }

    // ── ONCE ─────────────────────────────────────────────────────────

    private fun scheduleOnce(
        stateName: String,
        phase: PhaseDef,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        if (stateName in onceFired) {
            Log.e(TAG, "[once] $stateName 已触发过，跳过")
            return emptyList()
        }
        onceFired += stateName
        val agentId = phase.agents.first()
        val agent = resolveAgent(agentId)
        if (agent == null) {
            Log.e(TAG, "[once] 未知 agent: $agentId")
            return emptyList()
        }
        val meetingState = MeetingState.valueOf(stateName)
        Log.e(TAG, "[once] $stateName → SendMic → ${agent.id} task=${phase.task}")
        return buildList {
            add(SchedulerAction.SendMic(agent, meetingState, phase.task, event.traceId, event.eventId))
            add(SchedulerAction.SetTimer("${stateName}_timeout", phase.timeoutMs))
        }
    }

    // ── PARALLEL ─────────────────────────────────────────────────────

    private fun scheduleParallel(
        stateName: String,
        phase: PhaseDef,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        val barrier = barriers[stateName]
        val meetingState = MeetingState.valueOf(stateName)

        // 首次进入：广播
        if (barrier == null) {
            val barrierEvents = phase.agents.map { phase.barrierEvent ?: "agent_ready" }
            Log.e(TAG, "[parallel] $stateName 首次进入，广播给 ${phase.agents}")
            barriers[stateName] = BarrierState(required = barrierEvents)
            return buildList {
                phase.agents.forEach { agentId ->
                    val agent = resolveAgent(agentId)
                    if (agent != null) {
                        add(SchedulerAction.SendMic(agent, meetingState, phase.task, event.traceId, event.eventId))
                    }
                }
                add(SchedulerAction.SetTimer("${stateName}_barrier_timeout", phase.timeoutMs))
            }
        }

        // 后续：屏障匹配
        val barrierEventTypes = setOf("agent_ready", "intel_baseline_ready", "intel_degraded",
            phase.barrierEvent).filterNotNull().toSet()
        if (event.event in barrierEventTypes) {
            barrier.receive(event.event)
            Log.e(TAG, "[parallel] $stateName 收到 ${event.event}，进度 ${barrier.receivedCount()}/${barrier.requiredCount()}")

            if (barrier.isSatisfied()) {
                Log.e(TAG, "[parallel] 屏障满足！发射 all_ready")
                barriers.remove(stateName)
                return listOf(SchedulerAction.EmitEvent("all_ready", event.traceId, event.eventId))
            }
        }
        return emptyList()
    }

    // ── ROUND ROBIN ──────────────────────────────────────────────────

    private fun scheduleRoundRobin(
        stateName: String,
        phase: PhaseDef,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        val idx = turnIndex.getOrDefault(stateName, 0)
        val round = roundCount.getOrDefault(stateName, 1)
        val maxRounds = phase.maxRounds

        Log.e(TAG, "[roundRobin] $stateName idx=$idx round=$round max=$maxRounds event=${event.event}")

        if (round > maxRounds) {
            Log.e(TAG, "[roundRobin] 超过最大轮次，发射 max_rounds_reached")
            return listOf(SchedulerAction.EmitEvent("max_rounds_reached", event.traceId, event.eventId))
        }

        val agentIds = phase.agents
        val nextAgentId = agentIds[idx % agentIds.size]
        val nextAgent = resolveAgent(nextAgentId)
        if (nextAgent == null) {
            Log.e(TAG, "[roundRobin] 未知 agent: $nextAgentId")
            return emptyList()
        }

        val newIdx = idx + 1
        val newRound = if (newIdx % agentIds.size == 0) round + 1 else round
        turnIndex[stateName] = newIdx
        roundCount[stateName] = newRound

        val meetingState = MeetingState.valueOf(stateName)
        val roundTask = "${phase.task}_round_$round"

        val actions = mutableListOf<SchedulerAction>(
            SchedulerAction.EmitEvent("mic_passed", event.traceId, event.eventId,
                mapOf("to" to nextAgentId, "round" to round)),
            SchedulerAction.SendMic(nextAgent, meetingState, roundTask, event.traceId, event.eventId),
            SchedulerAction.SetTimer("${stateName}_speech_timeout", phase.timeoutMs),
        )

        // Pre-hooks from DSL
        for (hook in phase.preHooks) {
            if (round == hook.round && newIdx % agentIds.size == 1) {
                val hookAgent = resolveAgent(hook.agent)
                if (hookAgent != null) {
                    actions.add(SchedulerAction.SendMic(hookAgent, meetingState, hook.task, event.traceId, event.eventId))
                }
            }
        }

        return actions
    }

    companion object {
        private const val TAG = "Scheduler"
    }
}

// ── Data Structures（保持兼容） ──────────────────────────────────────

/**
 * 屏障状态 — multiset 匹配
 */
class BarrierState(val required: List<String>) {
    private val receivedCounts = mutableMapOf<String, Int>()

    fun receive(eventType: String) {
        receivedCounts[eventType] = (receivedCounts[eventType] ?: 0) + 1
    }

    fun isSatisfied(): Boolean {
        val requiredCounts = required.groupingBy { it }.eachCount()
        return requiredCounts.all { (type, needed) ->
            (receivedCounts[type] ?: 0) >= needed
        }
    }

    fun receivedCount(): Int = receivedCounts.values.sum()
    fun requiredCount(): Int = required.size
    fun receivedSummary(): Map<String, Int> = receivedCounts.toMap()
}

sealed class SchedulerAction {
    data class SendMic(
        val agent: AgentRole,
        val phase: MeetingState,
        val task: String,
        val traceId: String,
        val causedBy: String,
    ) : SchedulerAction()

    data class EmitEvent(
        val eventType: String,
        val traceId: String,
        val causedBy: String,
        val payload: Map<String, Any> = emptyMap(),
    ) : SchedulerAction()

    data class SetTimer(
        val timerId: String,
        val delayMs: Long,
    ) : SchedulerAction()

    data class CancelTimer(val timerId: String) : SchedulerAction()
}
