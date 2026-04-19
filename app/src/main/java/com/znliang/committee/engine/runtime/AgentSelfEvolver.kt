package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.data.db.AgentEvolutionEntity
import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  AgentSelfEvolver — 通用超级智能体进化引擎基类
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  参考 Hermes Agent 三层进化架构，为每个 Agent 提供统一的进化骨架：
 *
 *  Layer 1 — 角色记忆 (Memory)
 *    - 从每次会议提取领域特定的经验教训
 *    - 追踪反复出现的问题/失误
 *    - 下次会议自动注入相关记忆
 *
 *  Layer 2 — 策略技能 (Skill)
 *    - 按场景归纳有效策略
 *    - 记住哪些分析框架/论证模式最有效
 *
 *  Layer 3 — 质量自评 + Prompt 进化 (Reflection → Evolution)
 *    - 会后评估自身表现
 *    - 高频建议自动触发 prompt 改写
 *
 *  每个子类只需实现：
 *    - roleName(): 角色标识
 *    - buildReflectionPrompt(): 构建领域专属的反思 prompt
 *    - parseAndPersist(): 解析反思结果并持久化
 *    - getPreMeetingMemory(): 获取会前注入的记忆
 */
abstract class AgentSelfEvolver(
    protected val systemLlm: SystemLlmService,
    protected val evolutionRepo: EvolutionRepository,
) {
    companion object {
        private const val TAG = "AgentSelfEvolver"
        // 高优先级建议累积阈值，触发 prompt 进化
        protected const val EVOLVE_THRESHOLD = 3
    }

    /** 子类声明自己的角色 ID */
    abstract fun roleName(): String

    /** 子类声明自己的显示名称（用于日志） */
    abstract fun displayName(): String

    /**
     * 🔥 核心方法：会后反思
     *
     * 子类实现此方法：
     * 1. 用 systemLlm 调用 LLM 生成领域特定的反思
     * 2. 返回结构化的反思结果
     */
    abstract suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection

    /**
     * 🔥 会前记忆注入
     *
     * 子类返回需要注入到该 Agent prompt 的历史记忆。
     * 参考 Hermes 的 memory prefetch — LLM 调用前预加载记忆。
     * 默认实现：取 MISTAKE + STRATEGY 经验。
     */
    open suspend fun getPreMeetingMemory(): List<String> {
        val mistakes = evolutionRepo.getExperiencesByRoleAndCategory(
            roleName(), "MISTAKE", 5
        )
        val strategies = evolutionRepo.getExperiencesByRoleAndCategory(
            roleName(), "STRATEGY", 3
        )
        return (mistakes + strategies)
            .filter { !it.appliedToPrompt }
            .map { it.content }
            .distinct()
            .take(5)
    }

    /**
     * 检查是否应该触发自动 prompt 进化。
     *
     * 参考 Hermes 的 creation_nudge：累积足够证据后自动触发。
     */
    open suspend fun shouldAutoEvolve(): Boolean {
        val unapplied = evolutionRepo.getUnappliedHighPriority(roleName())
        if (unapplied.size < EVOLVE_THRESHOLD) return false
        val distinctMeetings = unapplied.map { it.meetingTraceId }.distinct().size
        return distinctMeetings >= 2
    }

    /**
     * 通用持久化方法：保存经验到进化库
     */
    protected suspend fun saveExperience(
        category: String,
        content: String,
        outcome: String = "NEUTRAL",
        priority: String = "MEDIUM",
        traceId: String,
    ) {
        evolutionRepo.saveExperience(AgentEvolutionEntity(
            agentRole = roleName(),
            meetingTraceId = traceId,
            category = category,
            content = content,
            outcome = outcome,
            priority = priority,
        ))
        Log.i(TAG, "[${roleName()}] 保存经验: $category/$priority")
    }

    protected fun log(msg: String) {
        Log.d(TAG, "[${roleName()}] $msg")
    }
}

/**
 * 通用反思结果基类
 *
 * 每个 Evolver 可以扩展此数据类添加领域专属字段。
 * 核心字段对所有 Agent 通用。
 */
open class AgentReflection(
    open val summary: String = "",
    open val suggestion: String = "",
    open val priority: String = "MEDIUM",
    open val traceId: String = "",
) {
    open fun isEmpty(): Boolean = summary.isBlank()
    fun isNotEmpty(): Boolean = !isEmpty()

    companion object {
        fun empty() = AgentReflection()
    }
}
