package com.committee.investing.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件持久化实体，对应规格文档 §8.1 events.jsonl
 * 使用 SQLite 替代 JSONL（规格文档 §9.2 推荐）
 */
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
    val id: Long = 0,
    val eventId: String,
    val ts: Long,                   // epoch millis
    val eventType: String,
    val agent: String,
    val traceId: String,
    val causedBy: String? = null,
    val step: String? = null,
    val payloadJson: String = "{}",
    val metricJson: String = "{}",
)

@Entity(tableName = "meeting_sessions")
data class MeetingSessionEntity(
    @PrimaryKey
    val traceId: String,
    val subject: String,
    val startTime: Long,
    val currentState: String,
    val currentRound: Int = 0,
    val rating: String? = null,
    val isCompleted: Boolean = false,
    val stateJson: String = "{}",   // looper-state snapshot
)

/**
 * 发言持久化实体 — 保存每个 Agent 的完整发言内容
 * 用于历史会议回放
 */
@Entity(
    tableName = "speeches",
    indices = [
        Index(value = ["traceId"]),
        Index(value = ["speechId"], unique = true),
    ]
)
data class SpeechEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val speechId: String,
    val traceId: String,
    val agentRole: String,          // AgentRole.id
    val round: Int,
    val summary: String,
    val content: String,
    val ts: Long,                   // epoch millis
)

/**
 * Agent 单独对话消息持久化
 * 每个 agent 有独立的聊天记录
 */
@Entity(
    tableName = "agent_chat_messages",
    indices = [
        Index(value = ["agentRole"]),
    ]
)
data class AgentChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val agentRole: String,          // AgentRole.id
    val role: String,               // "user" or "assistant"
    val content: String,
    val ts: Long,                   // epoch millis
)
