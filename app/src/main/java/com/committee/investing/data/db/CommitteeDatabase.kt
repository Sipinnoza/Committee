package com.committee.investing.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        MeetingSessionEntity::class,
        SpeechEntity::class,
        AgentChatMessageEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class CommitteeDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sessionDao(): MeetingSessionDao
    abstract fun speechDao(): SpeechDao
    abstract fun agentChatDao(): AgentChatDao
}
