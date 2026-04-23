package com.znliang.committee.engine.runtime

import com.znliang.committee.data.db.AgentEvolutionEntity
import com.znliang.committee.data.db.AgentSkillEntity

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  EvolvableAgent — 可自我进化的 Agent 接口
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  灵感来自 Hermes Agent 的三层自我进化机制：
 *    1. Memory（记忆）—— 跨会议的经验持久化
 *    2. Skill（技能）—— 可复用的策略模板
 *    3. Reflection（反思）—— 会后自评 + prompt 自动优化
 */

// ── 经验类别 ────────────────────────────────────────────────────

enum class ExperienceCategory(val displayName: String) {
    STRATEGY("策略洞察"),
    MISTAKE("错误教训"),
    INSIGHT("市场洞察"),
    PROMPT_FIX("Prompt修复"),
}

enum class EvolutionOutcome { POSITIVE, NEGATIVE, NEUTRAL }

enum class EvolutionPriority { HIGH, MEDIUM, LOW }

// ── 数据类 ──────────────────────────────────────────────────────

data class AgentExperience(
    val id: Long = 0,
    val agentRole: String,
    val meetingTraceId: String,
    val category: ExperienceCategory,
    val content: String,
    val outcome: EvolutionOutcome,
    val priority: EvolutionPriority,
    val appliedToPrompt: Boolean = false,
) {
    fun toEntity() = AgentEvolutionEntity(
        id = id,
        agentRole = agentRole,
        meetingTraceId = meetingTraceId,
        category = category.name,
        content = content,
        outcome = outcome.name,
        priority = priority.name,
        appliedToPrompt = appliedToPrompt,
    )

    companion object {
        fun fromEntity(e: AgentEvolutionEntity) = AgentExperience(
            id = e.id,
            agentRole = e.agentRole,
            meetingTraceId = e.meetingTraceId,
            category = ExperienceCategory.valueOf(e.category),
            content = e.content,
            outcome = EvolutionOutcome.valueOf(e.outcome),
            priority = EvolutionPriority.valueOf(e.priority),
            appliedToPrompt = e.appliedToPrompt,
        )
    }
}

data class MeetingOutcome(
    val subject: String,
    val finalRating: String?,
    val agentVote: String?,       // BULL or BEAR
    val voteCorrect: Boolean?,    // 投票是否与最终评级方向一致
    val rounds: Int,
    val totalMessages: Int,
    val myMessages: Int,
    val consensus: Boolean,
)

data class SelfEvaluation(
    val score: Float,             // 0.0 - 1.0
    val strengths: String,
    val weaknesses: String,
    val lessonsLearned: String,
    val category: ExperienceCategory,
    val priority: EvolutionPriority,
)

// ── 可进化 Agent 接口 ────────────────────────────────────────────

/**
 * 所有 Agent 的默认实现都在伴生对象中（避免每个 Agent 重复）。
 * 具体 Agent 只需实现 Agent 接口即可，EvolvableAgent 的方法
 * 由 EvolutionEngine 在 runtime 层统一调度。
 */
interface EvolvableAgent : Agent {

    /**
     * 从经验库中检索与当前上下文相关的经验。
     * 默认实现：取最近的 MISTAKE + STRATEGY 类经验。
     */
    suspend fun recallRelevantExperience(
        board: Blackboard,
        evolutionDao: com.znliang.committee.data.db.AgentEvolutionDao,
    ): List<AgentExperience> {
        val mistakes = evolutionDao.getByRoleAndCategory(role, "MISTAKE", 5)
        val strategies = evolutionDao.getByRoleAndCategory(role, "STRATEGY", 3)
        val insights = evolutionDao.getByRoleAndCategory(role, "INSIGHT", 2)
        return (mistakes + strategies + insights)
            .map { AgentExperience.fromEntity(it) }
            .sortedBy { it.priority.ordinal }
            .take(5)
    }

    /**
     * 将经验 + 技能注入到 prompt 中。
     * 类似 Hermes 的 memory injection — 在 system prompt 末尾追加经验上下文。
     */
    fun enrichPrompt(
        basePrompt: String,
        experiences: List<AgentExperience>,
        skills: List<AgentSkillEntity>,
    ): String {
        if (experiences.isEmpty() && skills.isEmpty()) return basePrompt

        val sb = StringBuilder(basePrompt.trimEnd())
        sb.appendLine()
        sb.appendLine()

        if (experiences.isNotEmpty()) {
            sb.appendLine("## 历史经验记忆（从过去会议中学到的教训）")
            experiences.forEach { exp ->
                val icon = when (exp.category) {
                    ExperienceCategory.MISTAKE -> "⚠️"
                    ExperienceCategory.STRATEGY -> "💡"
                    ExperienceCategory.INSIGHT -> "🔍"
                    ExperienceCategory.PROMPT_FIX -> "🔧"
                }
                val priorityMark = when (exp.priority) {
                    EvolutionPriority.HIGH -> "【重要】"
                    EvolutionPriority.MEDIUM -> ""
                    EvolutionPriority.LOW -> ""
                }
                sb.appendLine("$icon $priorityMark${exp.content}")
            }
            sb.appendLine()
        }

        if (skills.isNotEmpty()) {
            sb.appendLine("## 已验证的策略技能（经过多次会议验证的有效策略）")
            skills.forEach { skill ->
                sb.appendLine("📌 ${skill.skillName}: ${skill.strategyContent}")
            }
            sb.appendLine()
            sb.appendLine("请参考以上经验和策略来指导你的发言。")
        }

        return sb.toString()
    }
}
