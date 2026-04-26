package com.znliang.committee.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 会议事件
 */
data class CommitteeEvent(
    val eventId: String = "evt_${UUID.randomUUID().toString().replace("-", "").take(12)}", // 事件唯一ID
    val ts: Instant = Instant.now(),           // 事件时间戳
    val event: String,                         // 事件类型名称
    val agent: String,                         // 触发事件的Agent角色ID
    val traceId: String,                       // 关联的会议追踪ID
    val causedBy: String? = null,              // 触发原因描述
    val payload: Map<String, Any> = emptyMap(), // 事件附加数据
)

/**
 * 话筒上下文，传给 Agent 的完整上下文
 */
data class MicContext(
    val traceId: String,              // 会议追踪ID
    val causedBy: String,             // 触发原因
    val round: Int,                   // 当前讨论轮次
    val phase: MeetingState,          // 当前会议阶段
    val subject: String,              // 会议主题
    val agentRoleId: String,          // 被分配话筒的Agent角色ID
    val task: String = "",            // 分配给Agent的具体任务
)

data class SpeechRecord(
    val agent: String,                 // 发言Agent角色ID
    val round: Int,                    // 所属轮次
    val summary: String,               // 发言摘要
    val content: String,               // 完整发言内容
    val timestamp: Instant = Instant.now(), // 发言时间戳
    val isStreaming: Boolean = false,   // 是否正在流式输出
    val id: String = "sp_${UUID.randomUUID().toString().replace("-", "").take(10)}", // 发言唯一ID
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
