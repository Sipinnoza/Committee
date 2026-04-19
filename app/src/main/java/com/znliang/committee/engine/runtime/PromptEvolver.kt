package com.znliang.committee.engine.runtime

import android.content.Context
import android.util.Log
import com.znliang.committee.data.db.AgentEvolutionEntity
import com.znliang.committee.data.db.PromptChangelogEntity
import com.znliang.committee.data.repository.EvolutionRepository
import java.io.File

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  PromptEvolver — Prompt 自动进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  类似 Hermes 的 skill_manage + memory 机制：
 *    1. 监控高优先级建议出现的频率
 *    2. 当同类建议连续出现 ≥ 阈值次 → 触发自动进化
 *    3. 用 LLM 改写 prompt（保留核心身份，注入新策略）
 *    4. 写回 filesDir/prompts/ — 下次会议自动加载
 *    5. 记录变更到 prompt_changelog — 用户可回滚
 */
class PromptEvolver(
    private val systemLlm: SystemLlmService,
    private val appContext: Context,
    private val evolutionRepo: EvolutionRepository,
) {
    companion object {
        private const val TAG = "PromptEvolver"
        /** 同类建议出现次数阈值 → 触发自动进化 */
        private const val AUTO_EVOLVE_THRESHOLD = 3
        /** 回溯窗口：只看最近 30 天的建议 */
        private const val LOOKBACK_MS = 30L * 24 * 3600 * 1000
    }

    /**
     * 尝试为指定角色自动进化 prompt。
     *
     * @return true 如果执行了进化
     */
    suspend fun tryAutoEvolve(
        role: String,
        currentPrompt: String,
        newSuggestion: String,
        meetingTraceId: String,
    ): Boolean {
        // 检查是否有足够多的高优先级未应用建议
        val unapplied = evolutionRepo.getUnappliedHighPriority(role)
        if (unapplied.size < AUTO_EVOLVE_THRESHOLD - 1) {
            Log.d(TAG, "[$role] 高优先级建议不足 ${unapplied.size}/${AUTO_EVOLVE_THRESHOLD - 1}，跳过自动进化")
            return false
        }

        // 检查最近是否有频繁的相似建议
        val since = System.currentTimeMillis() - LOOKBACK_MS
        val recentExperiences = evolutionRepo.getExperiencesByRole(role, 30)
            .filter { it.createdAt > since && it.priority == "HIGH" && !it.appliedToPrompt }

        if (recentExperiences.size < AUTO_EVOLVE_THRESHOLD) {
            Log.d(TAG, "[$role] 近期高优先级经验不足，跳过")
            return false
        }

        Log.i(TAG, "[$role] 🧬 触发自动进化！${recentExperiences.size} 条高优先级建议")

        // 收集所有建议内容
        val allSuggestions = recentExperiences.map { it.content }.joinToString("\n") { "- $it" }

        // 调用 LLM 改写 prompt
        val evolvePrompt = """你是一个AI系统的Prompt优化专家。

## 当前 System Prompt
$currentPrompt

## 来自多次会议的自我反思建议（Agent自己的经验积累）
$allSuggestions

## 最新建议
$newSuggestion

## 任务
请基于以上建议，优化这个 System Prompt。要求：
1. 保留角色的核心定位和职责不变
2. 将建议中的有效策略融合到 Prompt 中（不是简单追加，而是有机融合）
3. 保持 Prompt 简洁（不超过800字）
4. 不要添加"自我优化记录"之类的元数据，直接给出最终的完整 prompt

直接输出优化后的完整 prompt，不要输出任何解释。"""

        val evolvedPrompt = systemLlm.quickCall(evolvePrompt)
        if (evolvedPrompt.isBlank() || evolvedPrompt.length < 50) {
            Log.w(TAG, "[$role] 进化结果为空或过短，放弃")
            return false
        }

        // 保存当前 prompt 到 changelog（用于回滚）
        val promptKey = getPromptKey(role)
        evolutionRepo.saveChangelog(PromptChangelogEntity(
            agentRole = role,
            changeType = "AUTO_EVOLVE",
            beforePrompt = currentPrompt,
            afterPrompt = evolvedPrompt,
            reason = "基于${recentExperiences.size}次会议的高优先级建议自动进化",
            sourceMeetingTraceId = meetingTraceId,
        ))

        // 写入本地文件
        val dir = File(appContext.filesDir, "prompts")
        dir.mkdirs()
        File(dir, "$promptKey.txt").writeText(evolvedPrompt)

        // 标记相关经验为已应用
        recentExperiences.forEach { exp ->
            evolutionRepo.markExperienceApplied(exp.id)
        }

        Log.i(TAG, "[$role] ✅ Prompt 自动进化完成！已保存到本地文件")
        return true
    }

    /**
     * 手动回滚到上一个 prompt 版本。
     */
    suspend fun rollback(role: String): Boolean {
        val latest = evolutionRepo.getLatestChangelog(role) ?: return false

        val promptKey = getPromptKey(role)
        val dir = File(appContext.filesDir, "prompts")
        dir.mkdirs()
        File(dir, "$promptKey.txt").writeText(latest.beforePrompt)

        evolutionRepo.markChangelogRolledBack(latest.id)
        Log.i(TAG, "[$role] 已回滚到上一版本")
        return true
    }

    private fun getPromptKey(role: String): String = when (role) {
        "analyst" -> "role_analyst"
        "risk_officer" -> "role_risk_officer"
        "strategy_validator" -> "role_strategist"
        "executor" -> "role_executor"
        "intel" -> "role_intel"
        "supervisor" -> "role_supervisor"
        else -> "role_$role"
    }
}
