package com.znliang.committee.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 会议事件
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
    val agentRoleId: String,
    val task: String = "",
)

data class SpeechRecord(
    val agent: String,
    val round: Int,
    val summary: String,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val isStreaming: Boolean = false,
    val id: String = "sp_${UUID.randomUUID().toString().replace("-", "").take(10)}",
    /** Agent 的推理过程（思考链路），用户可展开查看 */
    val reasoning: String = "",
    /** 内联投票标签 — 如 "Agree", "Score=8", "Stance=看涨" */
    val voteLabel: String = "",
    /** 是否为阶段转换事件（agent=="system" 时有效） */
    val isPhaseTransition: Boolean = false,
    /** 是否为共识达成事件（agent=="system" 时有效） */
    val isConsensusEvent: Boolean = false,
    /** 是否为会前议程事件（agent=="system" 时有效） */
    val isAgendaEvent: Boolean = false,
)
