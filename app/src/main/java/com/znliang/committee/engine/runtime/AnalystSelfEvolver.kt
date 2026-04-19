package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  AnalystSelfEvolver — 分析师专属自我进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Layer 1 — 逻辑链记忆 (Memory)
 *    - 追踪"增长驱动→利润兑现→估值重塑"逻辑链的完整性
 *    - 记住哪些论据被风险官有效反驳（逻辑弱点）
 *    - 估值锚点的历史准确度
 *
 *  Layer 2 — 看多策略技能 (Skill)
 *    - 归纳有效的看多论证模式
 *    - 按行业/市值/阶段分类的最佳论证框架
 *
 *  Layer 3 — 论证质量自评 + Prompt 进化
 *    - 评估论据是否被充分反驳
 *    - 检测是否遗漏关键增长驱动因素
 */
class AnalystSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "analyst"
    override fun displayName() = "分析师"

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        val myMessages = board.messages.filter { it.role == "analyst" }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val mySpeeches = myMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(400)}"
        }

        val riskRebuttals = board.messages
            .filter { it.role == "risk_officer" }
            .joinToString("\n") { "[风险官反驳] ${it.content.take(200)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识方向: ${if (board.bullRatio() > 0.5f) "看多" else "看空"}\n")
            append("我的投票: ${board.votes["analyst"]?.let { if (it.agree) "BULL" else "BEAR" } ?: "无"}\n")
            append("总轮次: ${board.round}\n")
        }

        val reflectPrompt = """你是分析师（看多方）的自我进化系统。

## 你在会议中的发言
$mySpeeches

## 风险官对你的反驳
$riskRebuttals

## 会议结果
$outcome

## 任务
从看多分析师的角度反思你的表现：

1. 逻辑链完整性：你的"增长驱动→利润兑现→估值重塑"逻辑链是否完整？
2. 论据韧性：风险官的反驳中，哪些点你没能有效回应？
3. 估值锚点：你引用的估值锚点是否合理？有没有更好的锚点？
4. 遗漏因素：有没有重要的增长驱动因素你忽略了？
5. 反驳策略：下次遇到类似风险官反驳，应该用什么策略？

输出格式（严格遵守）：
LOGIC_CHAIN_SCORE: 1-10 — （一句话说明逻辑链完整度）
REBUTTAL_WEAKNESS: 你没能有效回应的反驳点（用分号分隔）
VALUATION_LESSON: 估值锚点的经验教训
MISSING_DRIVERS: 你遗漏的重要增长驱动因素
STRATEGY_TIP: 下次同类标的的看多论证策略
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()
        return parseAnalystReflection(raw, meetingTraceId)
    }

    private suspend fun parseAnalystReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("LOGIC_CHAIN_SCORE:", ignoreCase = true) ->
                    summary = t.substringAfter(":").trim()
                t.startsWith("REBUTTAL_WEAKNESS:", ignoreCase = true) -> {
                    val weakness = t.substringAfter(":").trim()
                    if (weakness.isNotBlank() && weakness != "无") {
                        saveExperience("MISTAKE", "逻辑弱点: $weakness", "NEGATIVE", "HIGH", traceId)
                    }
                }
                t.startsWith("VALUATION_LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience("INSIGHT", "估值经验: $lesson", traceId = traceId)
                    }
                }
                t.startsWith("MISSING_DRIVERS:", ignoreCase = true) -> {
                    val drivers = t.substringAfter(":").trim()
                    if (drivers.isNotBlank() && drivers != "无") {
                        saveExperience("INSIGHT", "遗漏驱动: $drivers", "NEGATIVE", "MEDIUM", traceId)
                    }
                }
                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience("STRATEGY", "看多策略: $tip", "POSITIVE", "MEDIUM", traceId)
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
