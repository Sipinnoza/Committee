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
    val id: Long = 0,
    val eventId: String,
    val ts: Long,
    val eventType: String,
    val agent: String,
    val traceId: String,
    val causedBy: String? = null,
    val payloadJson: String = "{}",
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
    val id: Long = 0,
    val speechId: String,
    val traceId: String,
    val agentRole: String,
    val round: Int,
    val summary: String,
    val content: String,
    val ts: Long,
)

@Entity(
    tableName = "agent_chat_messages",
    indices = [
        Index(value = ["agentRole"]),
    ]
)
data class AgentChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val agentRole: String,
    val role: String,
    val content: String,
    val ts: Long,
)
