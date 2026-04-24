package com.znliang.committee.data.repository

import com.znliang.committee.data.db.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  EvolutionRepository — Agent 进化数据仓库
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  统一管理 4 张进化相关表的 CRUD：
 *    - agent_evolution    经验记忆
 *    - agent_skills       策略技能
 *    - prompt_changelog   prompt 变更历史
 *    - meeting_outcomes   会议结果追踪
 */
@Singleton
class EvolutionRepository @Inject constructor(
    private val evolutionDao: AgentEvolutionDao,
    private val skillDao: AgentSkillDao,
    private val changelogDao: PromptChangelogDao,
    private val outcomeDao: MeetingOutcomeDao,
) {

    // ── 经验记忆 ──────────────────────────────────────────────

    suspend fun saveExperience(entity: AgentEvolutionEntity): Long =
        evolutionDao.insert(entity)

    suspend fun getExperiencesByRole(role: String, limit: Int = 50): List<AgentEvolutionEntity> =
        evolutionDao.getByRole(role, limit)

    suspend fun getUnappliedHighPriority(role: String): List<AgentEvolutionEntity> =
        evolutionDao.getUnappliedHighPriority(role)

    suspend fun countSimilarExperiences(role: String, pattern: String, since: Long): Int =
        evolutionDao.countSimilar(role, pattern, since)

    suspend fun markExperienceApplied(id: Long) =
        evolutionDao.markApplied(id)

    suspend fun getRecentUnapplied(role: String, limit: Int = 10): List<AgentEvolutionEntity> =
        evolutionDao.getRecentUnapplied(role, limit)

    suspend fun getExperiencesByRoleAndCategory(role: String, category: String, limit: Int = 20): List<AgentEvolutionEntity> =
        evolutionDao.getByRoleAndCategory(role, category, limit)

    /** 暴露底层 DAO 给 EvolvableAgent 的 recallRelevantExperience — 计划迁移到 repo 方法 */
    @Deprecated("Use repository methods instead of direct DAO access", level = DeprecationLevel.WARNING)
    fun evolutionDao(): AgentEvolutionDao = evolutionDao

    // ── 技能库 ────────────────────────────────────────────────

    suspend fun saveSkill(entity: AgentSkillEntity): Long =
        skillDao.insert(entity)

    suspend fun getSkillsByRole(role: String): List<AgentSkillEntity> =
        skillDao.getByRole(role)

    suspend fun getVerifiedSkills(role: String, minConfidence: Float = 1f): List<AgentSkillEntity> =
        skillDao.getVerifiedSkills(role, minConfidence)

    suspend fun updateSkillUsage(id: Long, ts: Long, confidence: Float) =
        skillDao.updateUsage(id, ts, confidence)

    suspend fun getSkillByName(role: String, name: String): AgentSkillEntity? =
        skillDao.getByName(role, name)

    // ── Prompt 变更 ──────────────────────────────────────────

    suspend fun saveChangelog(entity: PromptChangelogEntity): Long =
        changelogDao.insert(entity)

    suspend fun getChangelogByRole(role: String, limit: Int = 20): List<PromptChangelogEntity> =
        changelogDao.getByRole(role, limit)

    suspend fun getLatestChangelog(role: String): PromptChangelogEntity? =
        changelogDao.getLatest(role)

    suspend fun markChangelogRolledBack(id: Long) =
        changelogDao.markRolledBack(id)

    // ── 会议结果 ──────────────────────────────────────────────

    suspend fun saveOutcome(entity: MeetingOutcomeEntity): Long =
        outcomeDao.insert(entity)

    suspend fun getOutcomesByRole(role: String, limit: Int = 20): List<MeetingOutcomeEntity> =
        outcomeDao.getByRole(role, limit)

    suspend fun getOutcomesByTrace(traceId: String): List<MeetingOutcomeEntity> =
        outcomeDao.getByTrace(traceId)

    suspend fun getAvgScoreSince(role: String, since: Long): Float? =
        outcomeDao.avgScoreSince(role, since)

    suspend fun getAccuracySince(role: String, since: Long): Float {
        val correct = outcomeDao.correctVotesSince(role, since)
        val total = outcomeDao.totalVotesSince(role, since)
        return if (total > 0) correct.toFloat() / total else 0f
    }
}
