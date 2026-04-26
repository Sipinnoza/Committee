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
    val id: Long = 0,                          // 自增主键
    val agentRole: String,                     // Agent角色ID
    val meetingTraceId: String,                // 关联会议追踪ID
    val category: String,          // STRATEGY, MISTAKE, INSIGHT, PROMPT_FIX
    val content: String,                       // 经验内容
    val outcome: String,           // POSITIVE, NEGATIVE, NEUTRAL
    val priority: String,          // HIGH, MEDIUM, LOW
    val appliedToPrompt: Boolean = false,      // 是否已应用到Prompt
    val createdAt: Long = System.currentTimeMillis(), // 创建时间
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
    val id: Long = 0,                          // 自增主键
    val agentRole: String,                     // Agent角色ID
    val skillName: String,                     // 技能名称
    val triggerCondition: String,  // JSON — 触发条件
    val strategyContent: String,   // 该场景下的策略
    val confidence: Float = 0f,    // 经验证次数
    val usageCount: Int = 0,                   // 使用次数
    val lastUsed: Long = 0,                    // 最后使用时间
    val createdAt: Long = System.currentTimeMillis(), // 创建时间
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
    val id: Long = 0,                          // 自增主键
    val agentRole: String,                     // Agent角色ID
    val changeType: String,        // AUTO_EVOLVE, MANUAL, SUGGESTION_APPLIED, ROLLBACK
    val beforePrompt: String,                  // 变更前的Prompt
    val afterPrompt: String,                   // 变更后的Prompt
    val reason: String,                        // 变更原因
    val sourceMeetingTraceId: String = "",     // 来源会议追踪ID
    val createdAt: Long = System.currentTimeMillis(), // 变更时间
    val isRolledBack: Boolean = false,         // 是否已回滚
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
    val id: Long = 0,                          // 自增主键
    val meetingTraceId: String,                // 会议追踪ID
    val agentRole: String,                     // Agent角色ID
    val subject: String,                       // 会议主题
    val finalRating: String?,                  // 最终评级结果
    val agentVote: String?,        // BULL or BEAR
    val voteCorrect: Boolean?,     // vote 是否与 final rating 方向一致
    val selfScore: Float = 0f,     // 0.0 - 1.0 自评
    val lessonsLearned: String = "",           // 经验教训
    val createdAt: Long = System.currentTimeMillis(), // 创建时间
)
