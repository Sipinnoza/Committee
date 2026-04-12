package com.committee.investing.data.repository

import com.committee.investing.data.db.EventDao
import com.committee.investing.data.db.EventEntity
import com.committee.investing.data.db.MeetingSessionDao
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.db.SpeechDao
import com.committee.investing.data.db.SpeechEntity
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.CommitteeEvent
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.SpeechRecord
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
    // ── Events ──────────────────────────────────────────────────────────────

    suspend fun appendEvent(event: CommitteeEvent): Boolean {
        // 幂等性：同一 eventId 不重复写入
        if (eventDao.exists(event.eventId)) return false
        val entity = event.toEntity()
        eventDao.insertEvent(entity)
        return true
    }

    suspend fun getEventsByTrace(traceId: String): List<CommitteeEvent> =
        eventDao.getEventsByTrace(traceId).map { it.toDomain() }

    fun observeEventsByTrace(traceId: String): Flow<List<CommitteeEvent>> =
        eventDao.observeEventsByTrace(traceId).map { list -> list.map { it.toDomain() } }

    /** 重放状态：从事件流重建当前 FSM 状态（规格文档 §8.3）*/
    suspend fun replayState(traceId: String): MeetingState {
        val events = getEventsByTrace(traceId)
        var state = MeetingState.IDLE
        for (evt in events) {
            for (t in MeetingState.TRANSITIONS) {
                if (t.on == evt.event && (t.from == null || t.from == state)) {
                    state = t.to
                    break
                }
            }
        }
        return state
    }

    suspend fun getProcessedEventIds(traceId: String): Set<String> =
        eventDao.getEventIds(traceId).toSet()

    // ── Sessions ─────────────────────────────────────────────────────────────

    suspend fun upsertSession(session: MeetingSessionEntity) =
        sessionDao.upsert(session)

    fun observeAllSessions(): Flow<List<MeetingSessionEntity>> =
        sessionDao.observeAllSessions()

    suspend fun getActiveSession(): MeetingSessionEntity? =
        sessionDao.getActiveSession()

    suspend fun updateSessionState(traceId: String, state: MeetingState, snapshot: Map<String, Any>) {
        sessionDao.updateState(traceId, state.name, gson.toJson(snapshot))
    }

    // ── Speeches ─────────────────────────────────────────────────────────────

    suspend fun saveSpeeches(traceId: String, speeches: List<SpeechRecord>) {
        val entities = speeches.filter { !it.isStreaming }.map { speech ->
            SpeechEntity(
                speechId = speech.id,
                traceId = traceId,
                agentRole = speech.agent.id,
                round = speech.round,
                summary = speech.summary,
                content = speech.content,
                ts = speech.timestamp.toEpochMilli(),
            )
        }
        // Filter out already-saved speeches
        val newEntities = entities.filter { !speechDao.exists(it.speechId) }
        if (newEntities.isNotEmpty()) {
            speechDao.insertAll(newEntities)
        }
    }

    suspend fun getSpeechesByTrace(traceId: String): List<SpeechRecord> =
        speechDao.getSpeechesByTrace(traceId).map { it.toSpeechRecord() }

    fun observeSpeechesByTrace(traceId: String): Flow<List<SpeechRecord>> =
        speechDao.observeSpeechesByTrace(traceId).map { list -> list.map { it.toSpeechRecord() } }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    private fun CommitteeEvent.toEntity() = EventEntity(
        eventId = eventId,
        ts = ts.toEpochMilli(),
        eventType = event,
        agent = agent,
        traceId = traceId,
        causedBy = causedBy,
        step = step,
        payloadJson = gson.toJson(payload),
        metricJson = gson.toJson(metric),
    )

    private fun EventEntity.toDomain() = CommitteeEvent(
        eventId = eventId,
        ts = java.time.Instant.ofEpochMilli(ts),
        event = eventType,
        agent = agent,
        traceId = traceId,
        causedBy = causedBy,
        step = step,
        payload = gson.fromJson(payloadJson, mapType) ?: emptyMap(),
        metric = gson.fromJson(metricJson, mapType) ?: emptyMap(),
    )

    private fun SpeechEntity.toSpeechRecord() = SpeechRecord(
        id = speechId,
        agent = AgentRole.fromId(agentRole) ?: AgentRole.SUPERVISOR,
        round = round,
        summary = summary,
        content = content,
        timestamp = java.time.Instant.ofEpochMilli(ts),
    )
}
