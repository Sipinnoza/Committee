package com.znliang.committee.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE traceId = :traceId ORDER BY ts ASC")
    suspend fun getEventsByTrace(traceId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE traceId = :traceId ORDER BY ts ASC")
    fun observeEventsByTrace(traceId: String): Flow<List<EventEntity>>

    @Query("SELECT eventId FROM events WHERE traceId = :traceId")
    suspend fun getEventIds(traceId: String): List<String>

    @Query("SELECT * FROM events ORDER BY ts DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 100): List<EventEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE eventId = :eventId)")
    suspend fun exists(eventId: String): Boolean
}

@Dao
interface MeetingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: MeetingSessionEntity)

    @Query("SELECT * FROM meeting_sessions WHERE traceId = :traceId")
    suspend fun getSession(traceId: String): MeetingSessionEntity?

    @Query("SELECT * FROM meeting_sessions ORDER BY startTime DESC")
    fun observeAllSessions(): Flow<List<MeetingSessionEntity>>

    @Query("SELECT * FROM meeting_sessions WHERE isCompleted = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): MeetingSessionEntity?

    @Query("UPDATE meeting_sessions SET currentState = :state WHERE traceId = :traceId")
    suspend fun updateState(traceId: String, state: String)
}

@Dao
interface SpeechDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(speech: SpeechEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(speeches: List<SpeechEntity>)

    @Query("SELECT * FROM speeches WHERE traceId = :traceId ORDER BY ts ASC")
    suspend fun getSpeechesByTrace(traceId: String): List<SpeechEntity>

    @Query("SELECT * FROM speeches WHERE traceId = :traceId ORDER BY ts ASC")
    fun observeSpeechesByTrace(traceId: String): Flow<List<SpeechEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM speeches WHERE speechId = :speechId)")
    suspend fun exists(speechId: String): Boolean
}

@Dao
interface AgentChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: AgentChatMessageEntity): Long

    @Query("SELECT * FROM agent_chat_messages WHERE agentRole = :roleId ORDER BY ts ASC")
    suspend fun getMessages(roleId: String): List<AgentChatMessageEntity>

    @Query("SELECT * FROM agent_chat_messages WHERE agentRole = :roleId ORDER BY ts ASC")
    fun observeMessages(roleId: String): Flow<List<AgentChatMessageEntity>>

    @Query("DELETE FROM agent_chat_messages WHERE agentRole = :roleId")
    suspend fun clearChat(roleId: String)
}
