package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.data.db.AgentSkillEntity
import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SkillLibrary — 策略技能化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  类似 Hermes 的 skill_manage：
 *    1. 从会议经验中提炼可复用的策略模式
 *    2. 用 LLM 识别经验中的通用策略 + 触发条件
 *    3. 存储为结构化技能（triggerCondition + strategyContent）
 *    4. 每次技能被使用并验证后增加 confidence
 *    5. confidence ≥ 阈值后，技能自动注入到 prompt
 */
class SkillLibrary(
    private val systemLlm: SystemLlmService,
    private val evolutionRepo: EvolutionRepository,
) {
    companion object {
        private const val TAG = "SkillLibrary"
        /** 需要至少 3 条同类经验才能提取技能 */
        private const val MIN_EXPERIENCES_FOR_SKILL = 3
    }

    /**
     * 尝试从经验中提取策略技能。
     *
     * @return true 如果成功提取了新技能
     */
    suspend fun tryExtractSkill(
        role: String,
        experiences: List<AgentExperience>,
    ): Boolean {
        // 只从 STRATEGY 和 INSIGHT 类经验中提取
        val candidates = experiences.filter {
            it.category == ExperienceCategory.STRATEGY || it.category == ExperienceCategory.INSIGHT
        }
        if (candidates.size < MIN_EXPERIENCES_FOR_SKILL) {
            Log.d(TAG, "[$role] 策略类经验不足 ${candidates.size}/${MIN_EXPERIENCES_FOR_SKILL}")
            return false
        }

        val expText = candidates.take(5).joinToString("\n") { "- [${it.category}] ${it.content}" }

        val extractPrompt = """你是一个策略提炼专家。

## 角色：$role
## 该角色在多次会议中积累的经验：
$expText

## 任务
请分析以上经验，提炼出 1 个可复用的策略技能。格式如下：

SKILL_NAME: 简短英文名（如 bear_market_defense, earnings_surprise_handler）
TRIGGER: JSON格式的触发条件，描述什么市场/标的环境下适用该策略
STRATEGY: 200字以内的策略内容，描述在这种情况下该角色应该如何发言

只输出以上3行，不要输出其他内容。"""

        val result = systemLlm.quickCall(extractPrompt)
        if (result.isBlank()) return false

        // 解析结果
        val name = result.lines()
            .firstOrNull { it.startsWith("SKILL_NAME:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return false
        val trigger = result.lines()
            .firstOrNull { it.startsWith("TRIGGER:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return false
        val strategy = result.lines()
            .firstOrNull { it.startsWith("STRATEGY:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return false

        // 检查是否已存在同名技能
        val existing = evolutionRepo.getSkillByName(role, name)
        if (existing != null) {
            // 更新已有技能
            evolutionRepo.updateSkillUsage(
                existing.id,
                System.currentTimeMillis(),
                existing.confidence + 0.5f,
            )
            Log.i(TAG, "[$role] 📌 技能已存在，更新：$name (confidence=${existing.confidence + 0.5f})")
            return true
        }

        // 保存新技能
        evolutionRepo.saveSkill(AgentSkillEntity(
            agentRole = role,
            skillName = name,
            triggerCondition = trigger,
            strategyContent = strategy,
            confidence = 0.5f, // 初始值，需要后续验证
            usageCount = 1,
            lastUsed = System.currentTimeMillis(),
        ))

        Log.i(TAG, "[$role] ✅ 新技能提炼成功：$name")
        return true
    }

    /**
     * 查找与当前会议相关的已验证技能。
     */
    suspend fun findRelevantSkills(
        role: String,
        subject: String,
        boardContext: String,
    ): List<AgentSkillEntity> {
        val allSkills = evolutionRepo.getVerifiedSkills(role, 1.0f)
        if (allSkills.isEmpty()) return emptyList()

        // 简单关键词匹配：用标的和上下文匹配触发条件
        return allSkills.filter { skill ->
            val trigger = skill.triggerCondition.lowercase()
            val context = (subject + " " + boardContext).lowercase()
            // 如果触发条件中的关键词出现在上下文中，则匹配
            trigger.split(",", "，", "、", " ")
                .map { it.trim() }
                .filter { it.length >= 2 }
                .any { context.contains(it) }
        }
    }
}
