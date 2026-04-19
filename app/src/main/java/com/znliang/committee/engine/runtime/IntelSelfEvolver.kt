package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.data.db.AgentEvolutionEntity
import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  IntelSelfEvolver — 情报官超级智能体进化引擎 v2
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  完全对标 Hermes Agent 自身的超级智能体架构：
 *
 *  Layer 1 — 情报记忆 (Memory)
 *    - 信息缺口记忆：跨会议追踪哪些信息经常缺失
 *    - 搜索策略记忆：记录"什么搜索策略对什么标的类型最有效"
 *    - 标的类型记忆：自动分类标类型，按类型匹配最佳策略
 *    - 每次会议前自动注入最相关的记忆
 *
 *  Layer 2 — 情报技能 (Skill)
 *    - 搜索策略模板：从历史经验提炼可复用的搜索模式
 *    - 数据源评估：记住哪些数据源最可靠
 *    - 信息完备度模型：学习"哪类标的需要覆盖哪些维度"
 *    - 验证反馈闭环：搜索策略被使用后跟踪效果，自动调整 confidence
 *
 *  Layer 3 — 质量自评 + Prompt 进化 (Reflection → Evolution)
 *    - 搜索效果评估：会后分析每次搜索的实际贡献度
 *    - 信息完备度评分：评估情报覆盖的全面性
 *    - 自适应策略建议：根据历史效果调整搜索策略
 *    - Prompt 自动进化：高优先级建议累积后触发 prompt 改写
 *
 *  v2 新增（对标 Hermes 自身进化特征）：
 *    1. 搜索策略验证反馈闭环 — 搜索效果可量化追踪
 *    2. 自适应搜索学习 — 从历史效果自动学习最优策略
 *    3. 标的类型感知 — 按标的特征选择搜索策略
 *    4. 搜索贡献度评估 — 会后评估每次搜索的实际价值
 */
class IntelSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "intel"
    override fun displayName() = "情报官"

    companion object {
        private const val TAG = "IntelSelfEvolver"

        // 搜索策略效果评估的最低会议数（至少经过3次会议才评估）
        private const val MIN_MEETINGS_FOR_STRATEGY_EVAL = 3

        // 高置信度技能阈值
        private const val HIGH_CONFIDENCE_THRESHOLD = 2.0f
    }

    // ── Layer 1: 情报记忆 ────────────────────────────────────────

    /**
     * 情报官专属：获取历史信息缺口记忆（用于预搜索 + prompt 注入）
     *
     * 对标 Hermes 的 memory injection — 每次任务前自动加载最相关的持久记忆。
     * 优先级：反复缺失 > 新近缺口 > 一般策略
     */
    suspend fun getGapMemory(): List<String> {
        // ① 反复出现的缺口（MISTAKE）— 最高优先级
        val mistakes = evolutionRepo.getExperiencesByRoleAndCategory("intel", "MISTAKE", 10)
        // ② 新近发现的信息缺口（INSIGHT）
        val insights = evolutionRepo.getExperiencesByRoleAndCategory("intel", "INSIGHT", 5)
        // ③ 已验证的搜索策略（STRATEGY）
        val strategies = evolutionRepo.getExperiencesByRoleAndCategory("intel", "STRATEGY", 3)

        val allExperiences = mistakes + insights + strategies

        return allExperiences
            .filter { !it.appliedToPrompt }
            .sortedByDescending {
                // 排序优先级：MISTAKE > INSIGHT > STRATEGY
                when (it.category) {
                    "MISTAKE" -> 100
                    "INSIGHT" -> 50
                    else -> 10
                }
            }
            .map { it.content }
            .distinct()
            .take(8)
    }

    /**
     * 🔥 v2: 获取与特定标的最相关的历史记忆
     *
     * 对标 Hermes 的 session_search — 根据当前上下文匹配过去经验。
     * 从历史会议结果中找出"类似标的"的经验教训。
     */
    suspend fun getRelevantMemoriesForSubject(subject: String): List<String> {
        val allExperiences = evolutionRepo.getExperiencesByRole("intel", 30)
        if (allExperiences.isEmpty()) return emptyList()

        // 简单关键词匹配：提取标的中的关键实体词
        val subjectKeywords = extractSubjectKeywords(subject)
        if (subjectKeywords.isEmpty()) return getGapMemory()

        // 匹配历史经验中与当前标的相关的内容
        val relevant = allExperiences
            .filter { exp ->
                subjectKeywords.any { kw -> exp.content.contains(kw, ignoreCase = true) }
            }
            .filter { !it.appliedToPrompt }
            .sortedByDescending { it.priority }
            .map { it.content }
            .distinct()
            .take(5)

        return if (relevant.isNotEmpty()) relevant else getGapMemory()
    }

    /**
     * 🔥 v2: 从标的名称中提取关键词用于记忆匹配
     */
    private fun extractSubjectKeywords(subject: String): List<String> {
        val keywords = mutableListOf<String>()
        // 提取中文实体（2-4个字的连续中文）
        val chineseRegex = Regex("[\\u4e00-\\u9fa5]{2,4}")
        chineseRegex.findAll(subject).forEach { match ->
            val word = match.value
            // 过滤掉通用词
            if (word !in listOf("怎么样", "好不好", "如何看", "值得买", "还能涨",
                    "要不要", "怎么样", "分析一下", "最新消息", "基本面")) {
                keywords.add(word)
            }
        }
        // 提取英文/数字（如股票代码）
        val codeRegex = Regex("[A-Za-z0-9]{2,}")
        codeRegex.findAll(subject).forEach { match ->
            keywords.add(match.value)
        }
        return keywords.distinct()
    }

    /**
     * 🔥 v2: 获取自适应搜索策略建议
     *
     * 对标 Hermes 的 skill_manage — 从已验证的技能中提取最相关的策略。
     * 根据标的关键词匹配已验证的搜索策略技能。
     */
    suspend fun getAdaptiveSearchStrategy(subject: String): String? {
        val verifiedSkills = evolutionRepo.getVerifiedSkills("intel", 1.0f)
        if (verifiedSkills.isEmpty()) return null

        val subjectKeywords = extractSubjectKeywords(subject)
        if (subjectKeywords.isEmpty()) return null

        // 找到与当前标的匹配度最高的技能
        val matched = verifiedSkills
            .map { skill ->
                val triggerKeywords = skill.triggerCondition
                    .split(",", "，", "、", " ", ";")
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                val matchScore = triggerKeywords.count { kw ->
                    subjectKeywords.any { sk -> sk.contains(kw, ignoreCase = true) || kw.contains(sk, ignoreCase = true) }
                }
                skill to matchScore
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        val bestMatch = matched.firstOrNull() ?: return null
        Log.d(TAG, "自适应搜索策略匹配: ${bestMatch.first.skillName} (score=${bestMatch.second})")

        return buildString {
            appendLine("## 🎯 自适应搜索策略（基于历史验证）")
            appendLine("策略名称: ${bestMatch.first.skillName}")
            appendLine("策略内容: ${bestMatch.first.strategyContent}")
            appendLine("置信度: ${(bestMatch.first.confidence * 50).toInt()}%")
            appendLine("已使用 ${bestMatch.first.usageCount} 次")
            appendLine()
            appendLine("请参考以上搜索策略来优化你的情报收集。")
        }
    }

    override suspend fun getPreMeetingMemory(): List<String> = getGapMemory()

    // ── Layer 2: 情报技能 ────────────────────────────────────────

    /**
     * 🔥 v2: 搜索效果追踪
     *
     * 对标 Hermes 的 skill 验证 — 用过 skill 后检查效果。
     * 在搜索策略被使用后，记录搜索结果的实际贡献度。
     */
    suspend fun trackSearchEffectiveness(
        query: String,
        resultLength: Int,
        wasUseful: Boolean,
        traceId: String,
    ) {
        val quality = when {
            resultLength == 0 -> "EMPTY"
            resultLength < 100 -> "POOR"
            wasUseful -> "GOOD"
            else -> "MODERATE"
        }

        saveExperience(
            category = "SEARCH_TRACE",
            content = "搜索[$quality]: query='$query' 结果${resultLength}字 ${if (wasUseful) "有效" else "低效"}",
            outcome = if (wasUseful) "POSITIVE" else "NEGATIVE",
            priority = if (wasUseful) "LOW" else "MEDIUM",
            traceId = traceId,
        )

        // 如果搜索低效，触发策略调整检查
        if (!wasUseful) {
            Log.i(TAG, "搜索低效记录: query='$query' — 下次反思时将评估策略调整")
        }
    }

    /**
     * 🔥 v2: 搜索策略效果汇总
     *
     * 返回历史搜索效果统计，用于反思时评估策略质量。
     */
    suspend fun getSearchEffectivenessSummary(): Map<String, Int> {
        val traces = evolutionRepo.getExperiencesByRoleAndCategory("intel", "SEARCH_TRACE", 50)
        val summary = mutableMapOf(
            "GOOD" to 0,
            "MODERATE" to 0,
            "POOR" to 0,
            "EMPTY" to 0,
        )
        for (trace in traces) {
            when {
                trace.content.contains("[GOOD]") -> summary["GOOD"] = (summary["GOOD"] ?: 0) + 1
                trace.content.contains("[MODERATE]") -> summary["MODERATE"] = (summary["MODERATE"] ?: 0) + 1
                trace.content.contains("[POOR]") -> summary["POOR"] = (summary["POOR"] ?: 0) + 1
                trace.content.contains("[EMPTY]") -> summary["EMPTY"] = (summary["EMPTY"] ?: 0) + 1
            }
        }
        return summary
    }

    // ── Layer 3: 质量自评 + Prompt 进化 ──────────────────────────

    /**
     * 🔥 情报官专属反思路径（v2 增强）
     *
     * 对标 Hermes 的完整反思闭环：
     * 1. 评估搜索效果（哪些搜索有效/无效）
     * 2. 评估信息完备度（是否遗漏关键信息）
     * 3. 评估信息准确度（是否被后续信息推翻）
     * 4. 自适应策略建议（基于历史搜索效果）
     * 5. Prompt 改进建议（结构化输出给 PromptEvolver）
     */
    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): IntelReflection {
        val intelMessages = board.messages.filter { it.role == "intel" }
        if (intelMessages.isEmpty()) return IntelReflection.empty()

        val allIntel = intelMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(500)}"
        }

        val otherMessages = board.messages
            .filter { it.role != "intel" && it.role != "supervisor" }
            .joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识: ${if (board.consensus) "是" else "否"}\n")
            append("情报轮数: ${intelMessages.size}\n")
            append("总讨论轮数: ${board.round}\n")
        }

        // 🔥 v2: 注入历史搜索效果统计
        val searchStats = getSearchEffectivenessSummary()
        val searchEffectSection = buildString {
            appendLine("## 历史搜索效果统计")
            appendLine("- 高效搜索: ${searchStats["GOOD"] ?: 0}次")
            appendLine("- 一般搜索: ${searchStats["MODERATE"] ?: 0}次")
            appendLine("- 低效搜索: ${searchStats["POOR"] ?: 0}次")
            appendLine("- 无结果搜索: ${searchStats["EMPTY"] ?: 0}次")
            val total = searchStats.values.sum()
            val goodRate = if (total > 0) ((searchStats["GOOD"] ?: 0).toFloat() / total * 100).toInt() else 0
            appendLine("- 搜索有效率: ${goodRate}%")
        }

        // 🔥 v2: 注入自适应策略上下文
        val adaptiveContext = getAdaptiveSearchStrategy(board.subject) ?: ""

        val reflectPrompt = """你是情报官的自我进化系统（v2 — 超级智能体）。

## 当前标的
${board.subject}

## 你在会议中提供的情报
$allIntel

## 其他成员的讨论（用于判断信息完备度）
$otherMessages

## 会议结果
$outcome

$searchEffectSection

$adaptiveContext

## 任务
请从以下维度进行深度自我评估（对标超级智能体的自我进化标准）：

### 维度1：搜索策略效果
- 你的搜索关键词是否精准？哪些搜索效果好？哪些效果差？
- 基于上面的搜索效果统计，你的搜索策略是否需要调整？

### 维度2：信息完备度
- 其他成员讨论中是否引用了你没有提供的数据？
- 是否有维度你完全没覆盖（如财务数据、行业动态、政策变化）？
- 信息缺口是否被及时发现并补充？

### 维度3：信息准确度
- 你提供的数据是否有被后续信息推翻的？
- 是否引用了过时或不准确的数据？

### 维度4：搜索策略自适应
- 对这类标的（如个股/行业/指数/加密货币），最优的搜索策略是什么？
- 从本次会议中学到的搜索技巧，下次如何复用？

输出格式（严格遵守）：
COMPLETENESS: 高/中/低 — （一句话说明）
ACCURACY: 高/中/低 — （一句话说明）
GAPS: 用分号分隔的信息缺口列表（如：Q3营收数据;海外业务占比;管理层指引）
RECURRING_GAPS: 从历史经验来看反复出现的缺口（如无则写"无"）
SEARCH_STRATEGY: 下次同类标的的搜索关键词模板（如：[标的]+财报+[年份]）
SEARCH_EFFECTIVENESS: 本次搜索有效率评估（高/中/低）+ 原因
ADAPTIVE_SUGGESTION: 针对这类标的的自适应策略建议（如能提炼出可复用模式）
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return IntelReflection.empty()
        return parseIntelReflection(raw, meetingTraceId)
    }

    private suspend fun parseIntelReflection(raw: String, traceId: String): IntelReflection {
        var completeness = ""
        var accuracy = ""
        var gaps = ""
        var recurringGaps = ""
        var searchStrategy = ""
        var searchEffectiveness = ""
        var adaptiveSuggestion = ""
        var promptFix = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("COMPLETENESS:", ignoreCase = true) ->
                    completeness = t.substringAfter(":").trim()
                t.startsWith("ACCURACY:", ignoreCase = true) ->
                    accuracy = t.substringAfter(":").trim()
                t.startsWith("GAPS:", ignoreCase = true) ->
                    gaps = t.substringAfter(":").trim()
                t.startsWith("RECURRING_GAPS:", ignoreCase = true) ->
                    recurringGaps = t.substringAfter(":").trim()
                t.startsWith("SEARCH_STRATEGY:", ignoreCase = true) ->
                    searchStrategy = t.substringAfter(":").trim()
                t.startsWith("SEARCH_EFFECTIVENESS:", ignoreCase = true) ->
                    searchEffectiveness = t.substringAfter(":").trim()
                t.startsWith("ADAPTIVE_SUGGESTION:", ignoreCase = true) ->
                    adaptiveSuggestion = t.substringAfter(":").trim()
                t.startsWith("PROMPT_FIX:", ignoreCase = true) ->
                    promptFix = t.substringAfter(":").trim()
                t.startsWith("PRIORITY:", ignoreCase = true) -> {
                    val v = t.substringAfter(":").trim().uppercase()
                    if (v in listOf("HIGH", "MEDIUM", "LOW")) priority = v
                }
            }
        }

        return IntelReflection(
            completeness = completeness,
            accuracy = accuracy,
            gaps = gaps,
            recurringGaps = recurringGaps,
            searchStrategy = searchStrategy,
            searchEffectiveness = searchEffectiveness,
            adaptiveSuggestion = adaptiveSuggestion,
            promptFix = promptFix,
            priority = priority,
            traceId = traceId,
        )
    }

    /**
     * 持久化情报官的反思结果（v2 增强）
     */
    suspend fun persistReflection(reflection: IntelReflection) {
        if (reflection.isEmpty()) return

        // ① 信息缺口 → INSIGHT
        if (reflection.gaps.isNotBlank() && reflection.gaps != "无") {
            val gapList = reflection.gaps.split(";", "；", "，", ",").map { it.trim() }.filter { it.isNotBlank() }
            for (gap in gapList) {
                saveExperience("INSIGHT", "信息缺口: $gap", "NEUTRAL", reflection.priority, reflection.traceId)
            }
        }

        // ② 反复出现的缺口 → 高优先级 MISTAKE
        if (reflection.recurringGaps.isNotBlank() && reflection.recurringGaps != "无") {
            val recurringList = reflection.recurringGaps.split(";", "；", "，", ",").map { it.trim() }.filter { it.isNotBlank() }
            for (gap in recurringList) {
                saveExperience("MISTAKE", "反复缺失的信息: $gap（需要在情报收集阶段优先获取）", "NEGATIVE", "HIGH", reflection.traceId)
            }
        }

        // ③ 搜索策略 → STRATEGY
        if (reflection.searchStrategy.isNotBlank()) {
            saveExperience("STRATEGY", "搜索策略: ${reflection.searchStrategy}", "POSITIVE", "MEDIUM", reflection.traceId)
        }

        // ④ 🔥 v2: 搜索效果评估 → STRATEGY（带效果标签）
        if (reflection.searchEffectiveness.isNotBlank()) {
            saveExperience(
                "STRATEGY",
                "搜索效果: ${reflection.searchEffectiveness}",
                if (reflection.searchEffectiveness.startsWith("高")) "POSITIVE" else "NEUTRAL",
                "MEDIUM",
                reflection.traceId,
            )
        }

        // ⑤ 🔥 v2: 自适应策略建议 → 高优先级 STRATEGY
        if (reflection.adaptiveSuggestion.isNotBlank()) {
            saveExperience(
                "STRATEGY",
                "自适应策略: ${reflection.adaptiveSuggestion}",
                "POSITIVE",
                "HIGH",
                reflection.traceId,
            )
        }

        // ⑥ Prompt 改进建议 → PROMPT_FIX
        if (reflection.promptFix.isNotBlank() && reflection.promptFix != "无需修改") {
            saveExperience("PROMPT_FIX", "Prompt改进: ${reflection.promptFix}", "NEUTRAL", reflection.priority, reflection.traceId)
        }
    }
}

/**
 * 情报官反思结果 v2（扩展 AgentReflection）
 *
 * v2 新增字段：
 * - searchEffectiveness: 搜索效果评估
 * - adaptiveSuggestion: 自适应策略建议
 */
data class IntelReflection(
    val completeness: String = "",
    val accuracy: String = "",
    val gaps: String = "",
    val recurringGaps: String = "",
    val searchStrategy: String = "",
    val searchEffectiveness: String = "",
    val adaptiveSuggestion: String = "",
    val promptFix: String = "",
    override val priority: String = "MEDIUM",
    override val traceId: String = "",
) : AgentReflection(
    summary = "完备度:$completeness 准确度:$accuracy 搜索效果:$searchEffectiveness",
    suggestion = promptFix,
    priority = priority,
    traceId = traceId,
) {
    override fun isEmpty(): Boolean = completeness.isBlank() && gaps.isBlank()

    companion object {
        fun empty() = IntelReflection()
    }
}
