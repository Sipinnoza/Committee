package com.committee.investing.engine

import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.CommitteeEvent
import com.committee.investing.domain.model.MeetingState

import android.util.Log

/**
 * 确定性调度器
 * 规格文档 §4.3 — 三种策略：once / parallel / round_robin
 * Agent 不控制何时执行，Scheduler 决定。
 */
class Scheduler {

    // ── 内部状态（可从快照恢复）──────────────────────────────────────────────
    private val onceFired = mutableSetOf<MeetingState>()
    private val barriers = mutableMapOf<MeetingState, BarrierState>()
    private val turnIndex = mutableMapOf<MeetingState, Int>()
    private val roundCount = mutableMapOf<MeetingState, Int>()

    // ── 公开接口 ─────────────────────────────────────────────────────────────

    /**
     * 根据当前状态 + 触发事件，返回下一批 Action
     */
    fun next(state: MeetingState, event: CommitteeEvent): List<SchedulerAction> {
        val cfg = PHASE_CONFIG[state]
        if (cfg == null) {
            Log.e(TAG, "[next] state=${state.name} event=${event.event} → 无 PHASE_CONFIG，返回空")
            return emptyList()
        }
        Log.e(TAG, "[next] state=${state.name} event=${event.event} strategy=${cfg.strategy} agents=${cfg.agents.map { it.id }}")
        val actions = when (cfg.strategy) {
            Strategy.ONCE       -> scheduleOnce(state, cfg, event)
            Strategy.PARALLEL   -> scheduleParallel(state, cfg, event)
            Strategy.ROUND_ROBIN -> scheduleRoundRobin(state, cfg, event)
        }
        Log.e(TAG, "[next] → 返回 ${actions.size} 个 Action: ${actions.map { it.javaClass.simpleName }}")
        return actions
    }

    fun reset() {
        onceFired.clear()
        barriers.clear()
        turnIndex.clear()
        roundCount.clear()
    }

    // ── Private: once ────────────────────────────────────────────────────────

    private fun scheduleOnce(
        state: MeetingState,
        cfg: PhaseConfig,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        if (state in onceFired) {
            Log.e(TAG, "[once] state=${state.name} 已触发过，跳过")
            return emptyList()
        }
        onceFired += state
        val agent = cfg.agents.first()
        Log.e(TAG, "[once] state=${state.name} → SendMic → ${agent.id} task=${cfg.task}")
        return buildList {
            add(SchedulerAction.SendMic(agent, state, cfg.task ?: "execute", event.traceId, event.eventId))
            add(SchedulerAction.SetTimer("${state.name}_timeout", cfg.timeoutMs))
        }
    }

    // ── Private: parallel ────────────────────────────────────────────────────

    private fun scheduleParallel(
        state: MeetingState,
        cfg: PhaseConfig,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        val barrier = barriers[state]

        // 首次进入：向所有 agent 广播 Task
        if (barrier == null) {
            val required = cfg.barrierEvents ?: cfg.agents.map { "agent_ready" }
            Log.e(TAG, "[parallel] state=${state.name} 首次进入，广播给 ${cfg.agents.map { it.id }}")
            Log.e(TAG, "[parallel] 屏障需要: $required")
            barriers[state] = BarrierState(
                required = required,
                received = mutableListOf(),
            )
            return buildList {
                cfg.agents.forEach { agent ->
                    add(SchedulerAction.SendMic(agent, state, cfg.task ?: "prepare", event.traceId, event.eventId))
                }
                add(SchedulerAction.SetTimer("${state.name}_barrier_timeout", cfg.timeoutMs))
            }
        }

        // 后续：收到 agent_ready / intel_baseline_ready → 追加到 received
        val barrierEventTypes = setOf("agent_ready", "intel_baseline_ready", "intel_degraded")
        if (event.event in barrierEventTypes) {
            barrier.received += event.event
            Log.e(TAG, "[parallel] state=${state.name} 收到 ${event.event}，进度 ${barrier.received.size}/${barrier.required.size}")
            Log.e(TAG, "[parallel]   received=${barrier.received} required=${barrier.required}")
            // 全部收到 → emit all_ready
            if (barrier.received.size >= barrier.required.size) {
                Log.e(TAG, "[parallel] 屏障满足！发射 all_ready")
                barriers.remove(state)
                return listOf(SchedulerAction.EmitEvent("all_ready", event.traceId, event.eventId))
            }
        } else {
            Log.e(TAG, "[parallel] state=${state.name} 忽略事件 ${event.event}（非屏障事件）")
        }
        return emptyList()
    }

    // ── Private: round_robin ─────────────────────────────────────────────────

    private fun scheduleRoundRobin(
        state: MeetingState,
        cfg: PhaseConfig,
        event: CommitteeEvent,
    ): List<SchedulerAction> {
        val idx = turnIndex.getOrDefault(state, 0)
        val round = roundCount.getOrDefault(state, 1)
        val maxRounds = cfg.maxRounds ?: 8

        Log.e(TAG, "[roundRobin] state=${state.name} idx=$idx round=$round maxRounds=$maxRounds event=${event.event}")

        if (round > maxRounds) {
            Log.e(TAG, "[roundRobin] 超过最大轮次，发射 max_rounds_reached")
            return listOf(SchedulerAction.EmitEvent("max_rounds_reached", event.traceId, event.eventId))
        }

        val agents = cfg.agents
        val nextAgent = agents[idx % agents.size]
        val newIdx = idx + 1
        val newRound = if (newIdx % agents.size == 0) round + 1 else round
        turnIndex[state] = newIdx
        roundCount[state] = newRound

        Log.e(TAG, "[roundRobin] → SendMic → ${nextAgent.id} (round=$round, newRound=$newRound)")

        val actions = mutableListOf<SchedulerAction>(
            SchedulerAction.EmitEvent("mic_passed", event.traceId, event.eventId,
                mapOf("to" to nextAgent.id, "round" to round)),
            SchedulerAction.SendMic(nextAgent, state, "debate_round_$round", event.traceId, event.eventId),
            SchedulerAction.SetTimer("${state.name}_speech_timeout", cfg.timeoutMs),
        )

        // Pre-hook：第 4 轮触发执行员预草拟（规格文档 §2.2）
        val hook = cfg.preHook
        if (hook != null && round == hook.round && newIdx % agents.size == 1) {
            actions.add(SchedulerAction.SendMic(hook.agent, state, hook.task, event.traceId, event.eventId))
        }

        return actions
    }

    // ── Phase Config ─────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Scheduler"
        val PHASE_CONFIG: Map<MeetingState, PhaseConfig> = mapOf(
            MeetingState.VALIDATING to PhaseConfig(
                strategy = Strategy.ONCE,
                agents = listOf(AgentRole.STRATEGIST),
                timeoutMs = 120_000L,
                task = "validate_entry",
            ),
            MeetingState.PREPPING to PhaseConfig(
                strategy = Strategy.PARALLEL,
                agents = listOf(AgentRole.ANALYST, AgentRole.RISK_OFFICER, AgentRole.INTEL, AgentRole.STRATEGIST),
                timeoutMs = 300_000L,
                task = "prepare",
                barrierEvents = listOf("agent_ready", "agent_ready", "intel_baseline_ready", "agent_ready"),
            ),
            MeetingState.PHASE1_DEBATE to PhaseConfig(
                strategy = Strategy.ROUND_ROBIN,
                agents = listOf(AgentRole.ANALYST, AgentRole.RISK_OFFICER, AgentRole.STRATEGIST),
                timeoutMs = 600_000L,
                maxRounds = 8,
                preHook = PreHook(round = 4, agent = AgentRole.EXECUTOR, task = "pre_draft"),
            ),
            MeetingState.PHASE1_ADJUDICATING to PhaseConfig(
                strategy = Strategy.ONCE,
                agents = listOf(AgentRole.SUPERVISOR),
                timeoutMs = 120_000L,
                task = "adjudicate",
            ),
            MeetingState.PHASE2_ASSESSMENT to PhaseConfig(
                strategy = Strategy.ROUND_ROBIN,
                agents = listOf(AgentRole.EXECUTOR, AgentRole.RISK_OFFICER),
                timeoutMs = 600_000L,
                maxRounds = 6,
            ),
            MeetingState.FINAL_RATING to PhaseConfig(
                strategy = Strategy.ONCE,
                agents = listOf(AgentRole.EXECUTOR),
                timeoutMs = 60_000L,
                task = "publish_rating",
            ),
            MeetingState.COMPLETED to PhaseConfig(
                strategy = Strategy.ONCE,
                agents = listOf(AgentRole.SUPERVISOR),
                timeoutMs = 120_000L,
                task = "write_minutes",
            ),
        )
    }
}

// ── Data Structures ───────────────────────────────────────────────────────────

enum class Strategy { ONCE, PARALLEL, ROUND_ROBIN }

data class PhaseConfig(
    val strategy: Strategy,
    val agents: List<AgentRole>,
    val timeoutMs: Long,
    val task: String? = null,
    val maxRounds: Int? = null,
    val barrierEvents: List<String>? = null,
    val preHook: PreHook? = null,
)

data class PreHook(val round: Int, val agent: AgentRole, val task: String)

data class BarrierState(
    val required: List<String>,
    val received: MutableList<String>,
)

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
