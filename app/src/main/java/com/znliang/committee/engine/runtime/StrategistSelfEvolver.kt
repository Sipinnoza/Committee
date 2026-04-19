package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  StrategistSelfEvolver — 策略师专属自我进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Layer 1 — 框架适用性记忆 (Memory)
 *    - 追踪使用的分析框架是否适合当前标的
 *    - 记住跨会议的一致性检查结果
 *    - 入场评估的一票否决判断准确度
 *
 *  Layer 2 — 中立框架技能 (Skill)
 *    - 归纳在不同市场环境下有效的策略框架
 *    - 按行业/市值/阶段分类的最佳中立分析模板
 *
 *  Layer 3 — 框架有效性自评 + Prompt 进化
 *    - 评估策略建议是否被最终结果验证
 *    - 检测是否过早或过晚给出策略建议
 */
class StrategistSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "strategy_validator"
    override fun displayName() = "策略师"

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        val myMessages = board.messages.filter { it.role == "strategy_validator" }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val mySpeeches = myMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(400)}"
        }

        val allDebate = board.messages
            .filter { it.role != "strategy_validator" && it.role != "supervisor" && it.role != "executor" }
            .joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识: ${if (board.consensus) "是" else "否"}\n")
            append("看多比例: ${(board.bullRatio() * 100).toInt()}%\n")
            append("我的投票: ${board.votes["strategy_validator"]?.let { if (it.agree) "BULL" else "BEAR" } ?: "无"}\n")
        }

        val reflectPrompt = """你是策略师（中立）的自我进化系统。

## 你在会议中的发言
$mySpeeches

## 多空辩论全貌
$allDebate

## 会议结果
$outcome

## 任务
从中立策略师的角度反思你的表现：

1. 框架选择：你使用的分析框架是否适合这个标的？
2. 时机把握：你是否在多空充分讨论后才给出策略建议？
3. 一致性检查：你是否有效识别了多空双方的逻辑矛盾？
4. 入场评估：你的入场/出场价位建议是否合理？
5. 风险收益比：你的仓位和止损建议是否平衡？

输出格式（严格遵守）：
FRAMEWORK_FIT: 1-10 — （一句话说明框架适用度）
TIMING_SCORE: 1-10 — （一句话说明时机把握）
CONSISTENCY_CHECK: 跨会议一致性检查发现的问题
ENTRY_LESSON: 入场评估的经验教训
RISK_REWARD_LESSON: 风险收益比建议的经验教训
STRATEGY_TIP: 下次同类标的的策略分析策略
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()
        return parseStrategistReflection(raw, meetingTraceId)
    }

    private suspend fun parseStrategistReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("FRAMEWORK_FIT:", ignoreCase = true) ->
                    summary = "框架:" + t.substringAfter(":").trim()
                t.startsWith("TIMING_SCORE:", ignoreCase = true) ->
                    summary += " | 时机:" + t.substringAfter(":").trim()
                t.startsWith("CONSISTENCY_CHECK:", ignoreCase = true) -> {
                    val issue = t.substringAfter(":").trim()
                    if (issue.isNotBlank() && issue != "无") {
                        saveExperience("INSIGHT", "一致性问题: $issue", traceId = traceId)
                    }
                }
                t.startsWith("ENTRY_LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience("INSIGHT", "入场评估经验: $lesson", traceId = traceId)
                    }
                }
                t.startsWith("RISK_REWARD_LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience("STRATEGY", "风险收益比经验: $lesson", "POSITIVE", "MEDIUM", traceId)
                    }
                }
                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience("STRATEGY", "策略框架: $tip", "POSITIVE", "MEDIUM", traceId)
                    }
                }
                t.startsWith("PROMPT_FIX:", ignoreCase = true) ->
                    suggestion = t.substringAfter(":").trim()
                t.startsWith("PRIORITY:", ignoreCase = true) -> {
                    val v = t.substringAfter(":").trim().uppercase()
                    if (v in listOf("HIGH", "MEDIUM", "LOW")) priority = v
                }
            }
        }

        return AgentReflection(
            summary = summary.ifBlank { raw.take(200) },
            suggestion = suggestion,
            priority = priority,
            traceId = traceId,
        )
    }
}
