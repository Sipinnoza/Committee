package com.committee.investing.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 投委会事件，与规格文档 §4.1 完全对应
 * 这是系统的唯一真相源（事件溯源）
 */
data class CommitteeEvent(
    val eventId: String = "evt_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    val ts: Instant = Instant.now(),
    val event: String,
    val agent: String,
    val traceId: String,
    val causedBy: String? = null,
    val step: String? = null,
    val payload: Map<String, Any> = emptyMap(),
    val metric: Map<String, Any> = emptyMap(),
) {
    companion object {
        /** 所有合法事件类型，与规格文档 §4.1 VALID_EVENTS 对应 */
        val VALID_EVENT_TYPES = setOf(
            // Agent 生命周期
            "agent_ready", "speech_complete", "consensus_reached",
            "intel_baseline_ready", "intel_degraded",
            // 会议流程
            "meeting_requested", "validation_passed", "validation_rejected",
            "all_ready", "max_rounds_reached", "plan_finalized",
            "rating_approved", "execution_confirmed", "meeting_cancelled",
            "minutes_published",
            // 仲裁
            "adjudication_complete",
            // 执行
            "execution_scheduled", "execution_started", "execution_completed",
            "execution_failed", "execution_overdue",
            // 系统（runtime 自动）
            "state_changed", "mic_passed", "agent_timeout", "alert_fired",
        )

        fun isValid(eventType: String) = eventType in VALID_EVENT_TYPES
    }
}

/**
 * 话筒上下文，传给 Agent 的完整上下文
 */
data class MicContext(
    val traceId: String,
    val causedBy: String,
    val round: Int,
    val phase: MeetingState,
    val subject: String,
    val opponentSummary: String = "",
    val previousSpeeches: List<SpeechRecord> = emptyList(),
    val agentRole: AgentRole,
    val task: String = "",
)

data class SpeechRecord(
    val agent: AgentRole,
    val round: Int,
    val summary: String,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val isStreaming: Boolean = false,
    val id: String = "sp_${UUID.randomUUID().toString().replace("-", "").take(10)}",
)

data class MeetingSession(
    val traceId: String,
    val subject: String,                         // 标的，如 "600028 石化"
    val startTime: Instant = Instant.now(),
    val state: MeetingState = MeetingState.IDLE,
    val currentRound: Int = 0,
    val speeches: List<SpeechRecord> = emptyList(),
    val rating: Rating? = null,
    val isCompleted: Boolean = false,
)
