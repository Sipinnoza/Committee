package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  RiskSelfEvolver — 风险官专属自我进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Layer 1 — 风险漏检记忆 (Memory)
 *    - 追踪"假设失效→业绩落空→估值重估→资本损失"风险链的覆盖度
 *    - 记住哪些风险被后续事件验证（漏检 = 严重错误）
 *    - 按行业分类的常见风险模式
 *
 *  Layer 2 — 看空策略技能 (Skill)
 *    - 归纳有效的风险识别框架
 *    - 按行业/市值/阶段分类的最佳看空论证模式
 *
 *  Layer 3 — 风险覆盖率自评 + Prompt 进化
 *    - 评估是否遗漏了关键风险
 *    - 检测假设攻击是否足够深入
 */
class RiskSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "risk_officer"
    override fun displayName() = "风险官"

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        val myMessages = board.messages.filter { it.role == "risk_officer" }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val mySpeeches = myMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(400)}"
        }

        val bullRebuttals = board.messages
            .filter { it.role == "analyst" }
            .joinToString("\n") { "[分析师反驳] ${it.content.take(200)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识方向: ${if (board.bullRatio() > 0.5f) "看多" else "看空"}\n")
            append("我的投票: ${board.votes["risk_officer"]?.let { if (it.agree) "BULL" else "BEAR" } ?: "无"}\n")
            append("总轮次: ${board.round}\n")
        }

        val reflectPrompt = """你是风险官（看空方）的自我进化系统。

## 你在会议中的发言
$mySpeeches

## 分析师对你的反驳
$bullRebuttals

## 会议结果
$outcome

## 任务
从风险官的角度反思你的表现：

1. 风险覆盖度：你是否覆盖了"假设失效→业绩落空→估值重估→资本损失"完整链条？
2. 假设攻击深度：你对看多假设的攻击是否足够深入？
3. 遗漏风险：有没有重要风险因素你完全没提到？
4. 情景分析：你的下行情景是否足够具体和可操作？
5. 反驳应对：分析师的反驳中，哪些你没能有效回应？

输出格式（严格遵守）：
RISK_COVERAGE: 1-10 — （一句话说明风险覆盖度）
ASSUMPTION_ATTACK: 假设攻击的深度评估和改进建议
MISSED_RISKS: 你遗漏的重要风险因素（用分号分隔）
DOWNSIDE_LESSON: 下行情景分析的经验教训
REBUTTAL_RESPONSE: 分析师反驳中你未能回应的点
STRATEGY_TIP: 下次同类标的的风险识别策略
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()
        return parseRiskReflection(raw, meetingTraceId)
    }

    private suspend fun parseRiskReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("RISK_COVERAGE:", ignoreCase = true) ->
                    summary = t.substringAfter(":").trim()
                t.startsWith("MISSED_RISKS:", ignoreCase = true) -> {
                    val risks = t.substringAfter(":").trim()
                    if (risks.isNotBlank() && risks != "无") {
                        saveExperience("MISTAKE", "风险漏检: $risks", "NEGATIVE", "HIGH", traceId)
                    }
                }
                t.startsWith("DOWNSIDE_LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience("INSIGHT", "下行情景经验: $lesson", traceId = traceId)
                    }
                }
                t.startsWith("REBUTTAL_RESPONSE:", ignoreCase = true) -> {
                    val weak = t.substringAfter(":").trim()
                    if (weak.isNotBlank() && weak != "无") {
                        saveExperience("INSIGHT", "反驳弱点: $weak", "NEGATIVE", "MEDIUM", traceId)
                    }
                }
                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience("STRATEGY", "风险识别策略: $tip", "POSITIVE", "MEDIUM", traceId)
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
