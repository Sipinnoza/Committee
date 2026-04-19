package com.znliang.committee.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── Agent 经验记忆 ──────────────────────────────────────────────

@Entity(
    tableName = "agent_evolution",
    indices = [
        Index("agentRole"),
        Index("meetingTraceId"),
        Index("category"),
    ]
)
data class AgentEvolutionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val agentRole: String,
    val meetingTraceId: String,
    val category: String,          // STRATEGY, MISTAKE, INSIGHT, PROMPT_FIX
    val content: String,
    val outcome: String,           // POSITIVE, NEGATIVE, NEUTRAL
    val priority: String,          // HIGH, MEDIUM, LOW
    val appliedToPrompt: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

// ── Agent 技能库 ────────────────────────────────────────────────

@Entity(
    tableName = "agent_skills",
    indices = [
        Index("agentRole"),
        Index("skillName"),
    ]
)
data class AgentSkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val agentRole: String,
    val skillName: String,
    val triggerCondition: String,  // JSON — 触发条件
    val strategyContent: String,   // 该场景下的策略
    val confidence: Float = 0f,    // 经验证次数
    val usageCount: Int = 0,
    val lastUsed: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

// ── Prompt 变更历史 ─────────────────────────────────────────────

@Entity(
    tableName = "prompt_changelog",
    indices = [
        Index("agentRole"),
        Index("createdAt"),
    ]
)
data class PromptChangelogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val agentRole: String,
    val changeType: String,        // AUTO_EVOLVE, MANUAL, SUGGESTION_APPLIED, ROLLBACK
    val beforePrompt: String,
    val afterPrompt: String,
    val reason: String,
    val sourceMeetingTraceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isRolledBack: Boolean = false,
)

// ── 会议结果追踪 ────────────────────────────────────────────────

@Entity(
    tableName = "meeting_outcomes",
    indices = [
        Index("meetingTraceId"),
        Index("agentRole"),
    ]
)
data class MeetingOutcomeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val meetingTraceId: String,
    val agentRole: String,
    val subject: String,
    val finalRating: String?,
    val agentVote: String?,        // BULL or BEAR
    val voteCorrect: Boolean?,     // vote 是否与 final rating 方向一致
    val selfScore: Float = 0f,     // 0.0 - 1.0 自评
    val lessonsLearned: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
