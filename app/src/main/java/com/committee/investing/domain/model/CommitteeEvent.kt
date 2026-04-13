package com.committee.investing.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 投委会事件
 */
data class CommitteeEvent(
    val eventId: String = "evt_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    val ts: Instant = Instant.now(),
    val event: String,
    val agent: String,
    val traceId: String,
    val causedBy: String? = null,
    val payload: Map<String, Any> = emptyMap(),
)

/**
 * 话筒上下文，传给 Agent 的完整上下文
 */
data class MicContext(
    val traceId: String,
    val causedBy: String,
    val round: Int,
    val phase: MeetingState,
    val subject: String,
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
