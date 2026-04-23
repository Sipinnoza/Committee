package com.znliang.committee.data.repository

import com.znliang.committee.data.db.*
import com.znliang.committee.domain.model.CommitteeEvent
import com.znliang.committee.domain.model.SpeechRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(
    private val eventDao: EventDao,
    private val sessionDao: MeetingSessionDao,
    private val speechDao: SpeechDao,
    private val gson: Gson,
) {

    // ── Events ────────────────────────────────────────────────────────────

    suspend fun saveEvent(event: CommitteeEvent) =
        eventDao.insertEvent(event.toEntity())

    suspend fun getEventsByTrace(traceId: String): List<CommitteeEvent> =
        eventDao.getEventsByTrace(traceId).map { it.toDomain() }

    fun observeEventsByTrace(traceId: String): Flow<List<CommitteeEvent>> =
        eventDao.observeEventsByTrace(traceId).map { list -> list.map { it.toDomain() } }

    suspend fun getProcessedEventIds(traceId: String): Set<String> =
        eventDao.getEventIds(traceId).toSet()

    suspend fun getSpeechesByTrace(traceId: String): List<SpeechRecord> =
        speechDao.getSpeechesByTrace(traceId).map { it.toSpeechRecord() }

    // ── Sessions ──────────────────────────────────────────────────────────

    suspend fun upsertSession(session: MeetingSessionEntity) =
        sessionDao.upsert(session)

    fun observeAllSessions(): Flow<List<MeetingSessionEntity>> =
        sessionDao.observeAllSessions()

    suspend fun getActiveSession(): MeetingSessionEntity? =
        sessionDao.getActiveSession()

    // ── Speeches ──────────────────────────────────────────────────────────

    suspend fun saveSpeeches(traceId: String, speeches: List<SpeechRecord>) {
        speechDao.insertAll(speeches.map { it.toEntity(traceId) })
    }

    fun observeSpeechesByTrace(traceId: String): Flow<List<SpeechRecord>> =
        speechDao.observeSpeechesByTrace(traceId).map { list -> list.map { it.toSpeechRecord() } }

    // ── Mappers ───────────────────────────────────────────────────────────

    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    private fun CommitteeEvent.toEntity() = EventEntity(
        eventId = eventId,
        ts = ts.toEpochMilli(),
        eventType = event,
        agent = agent,
        traceId = traceId,
        causedBy = causedBy,
        payloadJson = gson.toJson(payload),
    )

    private fun EventEntity.toDomain() = CommitteeEvent(
        eventId = eventId,
        ts = java.time.Instant.ofEpochMilli(ts),
        event = eventType,
        agent = agent,
        traceId = traceId,
        causedBy = causedBy,
        payload = gson.fromJson(payloadJson, mapType) ?: emptyMap(),
    )

    private fun SpeechRecord.toEntity(traceId: String) = SpeechEntity(
        speechId = id,
        traceId = traceId,
        agentRole = agent,
        round = round,
        summary = summary,
        content = content,
        ts = timestamp.toEpochMilli(),
    )

    private fun SpeechEntity.toSpeechRecord() = SpeechRecord(
        id = speechId,
        agent = agentRole,
        round = round,
        summary = summary,
        content = content,
        timestamp = java.time.Instant.ofEpochMilli(ts),
    )
}
