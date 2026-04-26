package com.znliang.committee.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["traceId"]),
        Index(value = ["eventId"], unique = true),
        Index(value = ["eventType"]),
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,                    // 自增主键
    val eventId: String,                 // 事件唯一标识
    val ts: Long,                        // 事件时间戳（毫秒）
    val eventType: String,               // 事件类型
    val agent: String,                   // 触发Agent角色ID
    val traceId: String,                 // 关联会议追踪ID
    val causedBy: String? = null,        // 触发原因
    val payloadJson: String = "{}",      // 事件附加数据JSON
)

@Entity(tableName = "meeting_sessions")
data class MeetingSessionEntity(
    @PrimaryKey
    val traceId: String,                 // 会议追踪ID（主键）
    val subject: String,                 // 会议主题
    val startTime: Long,                 // 会议开始时间戳
    val currentState: String,            // 当前会议状态
    val currentRound: Int = 0,           // 当前讨论轮次
    val rating: String? = null,          // 最终评级结果
    val isCompleted: Boolean = false,    // 会议是否已完成
    // v10: 完整决策数据
    val summary: String = "",            // 会议摘要
    val consensus: Boolean = false,      // 是否达成共识
    val decisionConfidence: Int = 0,     // 决策置信度(0-100)
    val confidenceBreakdown: String = "", // 置信度明细
    val votesJson: String = "",          // 投票数据JSON
    val contributionsJson: String = "",  // 贡献度评分JSON
    val userOverrideRating: String? = null, // 用户覆写的评级
    val userOverrideReason: String = "", // 用户覆写理由
    val errorMessage: String? = null,    // 错误信息
    val executionPlan: String? = null,   // 执行计划
)

@Entity(
    tableName = "speeches",
    indices = [
        Index(value = ["traceId"]),
        Index(value = ["speechId"], unique = true),
    ]
)
data class SpeechEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,                    // 自增主键
    val speechId: String,                // 发言唯一标识
    val traceId: String,                 // 关联会议追踪ID
    val agentRole: String,               // 发言Agent角色ID
    val round: Int,                      // 所属轮次
    val summary: String,                 // 发言摘要
    val content: String,                 // 完整发言内容
    val ts: Long,                        // 发言时间戳
    // v10: 推理过程
    val reasoning: String = "",          // Agent推理过程（思考链）
    // v11: 投票标签
    val voteLabel: String = "",          // 内联投票标签
)

@Entity(
    tableName = "agent_chat_messages",
    indices = [
        Index(value = ["agentRole"]),
    ]
)
data class AgentChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,                    // 自增主键
    val agentRole: String,               // Agent角色ID
    val role: String,                    // 消息角色（user/assistant）
    val content: String,                 // 消息内容
    val ts: Long,                        // 消息时间戳
)
