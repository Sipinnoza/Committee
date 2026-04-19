package com.znliang.committee.data.db

import androidx.room.*

// ── Agent 经验 DAO ──────────────────────────────────────────────

@Dao
interface AgentEvolutionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AgentEvolutionEntity): Long

    @Query("SELECT * FROM agent_evolution WHERE agentRole = :role ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByRole(role: String, limit: Int = 50): List<AgentEvolutionEntity>

    @Query("SELECT * FROM agent_evolution WHERE agentRole = :role AND category = :category ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByRoleAndCategory(role: String, category: String, limit: Int = 20): List<AgentEvolutionEntity>

    @Query("SELECT * FROM agent_evolution WHERE agentRole = :role AND priority = 'HIGH' AND appliedToPrompt = 0 ORDER BY createdAt DESC")
    suspend fun getUnappliedHighPriority(role: String): List<AgentEvolutionEntity>

    @Query("SELECT COUNT(*) FROM agent_evolution WHERE agentRole = :role AND content LIKE :pattern AND createdAt > :since")
    suspend fun countSimilar(role: String, pattern: String, since: Long): Int

    @Query("UPDATE agent_evolution SET appliedToPrompt = 1 WHERE id = :id")
    suspend fun markApplied(id: Long)

    @Query("SELECT * FROM agent_evolution WHERE agentRole = :role AND appliedToPrompt = 0 ORDER BY priority DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecentUnapplied(role: String, limit: Int = 10): List<AgentEvolutionEntity>
}

// ── Agent 技能 DAO ──────────────────────────────────────────────

@Dao
interface AgentSkillDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AgentSkillEntity): Long

    @Query("SELECT * FROM agent_skills WHERE agentRole = :role ORDER BY confidence DESC")
    suspend fun getByRole(role: String): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE agentRole = :role AND confidence >= :minConfidence ORDER BY usageCount DESC")
    suspend fun getVerifiedSkills(role: String, minConfidence: Float = 1f): List<AgentSkillEntity>

    @Query("UPDATE agent_skills SET usageCount = usageCount + 1, lastUsed = :ts, confidence = :confidence WHERE id = :id")
    suspend fun updateUsage(id: Long, ts: Long, confidence: Float)

    @Query("SELECT * FROM agent_skills WHERE agentRole = :role AND skillName = :name LIMIT 1")
    suspend fun getByName(role: String, name: String): AgentSkillEntity?
}

// ── Prompt 变更 DAO ─────────────────────────────────────────────

@Dao
interface PromptChangelogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PromptChangelogEntity): Long

    @Query("SELECT * FROM prompt_changelog WHERE agentRole = :role AND isRolledBack = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByRole(role: String, limit: Int = 20): List<PromptChangelogEntity>

    @Query("SELECT * FROM prompt_changelog WHERE agentRole = :role AND isRolledBack = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(role: String): PromptChangelogEntity?

    @Query("UPDATE prompt_changelog SET isRolledBack = 1 WHERE id = :id")
    suspend fun markRolledBack(id: Long)
}

// ── 会议结果 DAO ────────────────────────────────────────────────

@Dao
interface MeetingOutcomeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MeetingOutcomeEntity): Long

    @Query("SELECT * FROM meeting_outcomes WHERE agentRole = :role ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByRole(role: String, limit: Int = 20): List<MeetingOutcomeEntity>

    @Query("SELECT * FROM meeting_outcomes WHERE meetingTraceId = :traceId")
    suspend fun getByTrace(traceId: String): List<MeetingOutcomeEntity>

    @Query("SELECT AVG(selfScore) FROM meeting_outcomes WHERE agentRole = :role AND createdAt > :since")
    suspend fun avgScoreSince(role: String, since: Long): Float?

    @Query("SELECT COUNT(*) FROM meeting_outcomes WHERE agentRole = :role AND voteCorrect = 1 AND createdAt > :since")
    suspend fun correctVotesSince(role: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM meeting_outcomes WHERE agentRole = :role AND createdAt > :since")
    suspend fun totalVotesSince(role: String, since: Long): Int
}
