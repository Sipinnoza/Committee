package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SupervisorSelfEvolver — 监督员专属自我进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Layer 1 — 调度节奏记忆 (Memory)
 *    - 追踪过早结束 vs 过晚结束的决策偏差
 *    - 记住不同标的类型需要的讨论深度
 *    - 共识预判准确度追踪
 *
 *  Layer 2 — 调度策略技能 (Skill)
 *    - 归纳最佳讨论节奏（何时介入、何时放任）
 *    - 按标的类型/分歧程度分类的结束条件模板
 *
 *  Layer 3 — 调度质量自评 + Prompt 进化
 *    - 评估点评是否推动了讨论进展
 *    - 检测是否过早或过晚终止讨论
 */
class SupervisorSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "supervisor"
    override fun displayName() = "监督员"

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        val myMessages = board.messages.filter { it.role == "supervisor" }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val myComments = myMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(300)}"
        }

        // 监督员视角：看全部讨论
        val allDebate = board.messages
            .filter { it.role != "supervisor" }
            .joinToString("\n") { "[${it.role} R${it.round}] ${it.content.take(150)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识: ${if (board.consensus) "是" else "否"}\n")
            append("总轮次: ${board.round}\n")
            append("总发言: ${board.messages.size}条\n")
            append("看多比例: ${(board.bullRatio() * 100).toInt()}%\n")
        }

        val reflectPrompt = """你是监督员的自我进化系统。

## 你在会议中的点评
$myComments

## 完整讨论记录
$allDebate

## 会议结果
$outcome

## 任务
从监督员的角度反思你的调度质量：

1. 讨论节奏：是否在合适时机介入？点评是否推动了讨论进展？
2. 结束时机：是否过早结束（讨论不充分）或过晚结束（浪费轮次）？
3. 共识引导：是否有效推动了多空双方达成共识？
4. 质量把关：是否有效识别并指出了论证中的逻辑漏洞？
5. 角色平衡：是否确保了每个角色都有充分发言机会？

输出格式（严格遵守）：
TIMING_SCORE: 1-10 — （一句话说明介入时机质量）
FINISH_JUDGMENT: 过早/合适/过晚 — （一句话说明结束时机）
CONSENSUS_GUIDANCE: 共识引导的效果评估
BALANCE_CHECK: 各角色发言平衡度评估
STRATEGY_TIP: 下次会议的调度策略改进
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()
        return parseSupervisorReflection(raw, meetingTraceId)
    }

    private suspend fun parseSupervisorReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("TIMING_SCORE:", ignoreCase = true) ->
                    summary = "时机:" + t.substringAfter(":").trim()
                t.startsWith("FINISH_JUDGMENT:", ignoreCase = true) ->
                    summary += " | 结束:" + t.substringAfter(":").trim()
                t.startsWith("CONSENSUS_GUIDANCE:", ignoreCase = true) -> {
                    val guidance = t.substringAfter(":").trim()
                    if (guidance.isNotBlank()) {
                        saveExperience("INSIGHT", "共识引导: $guidance", traceId = traceId)
                    }
                }
                t.startsWith("BALANCE_CHECK:", ignoreCase = true) -> {
                    val balance = t.substringAfter(":").trim()
                    if (balance.isNotBlank()) {
                        saveExperience("INSIGHT", "角色平衡: $balance", traceId = traceId)
                    }
                }
                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience("STRATEGY", "调度策略: $tip", "POSITIVE", "MEDIUM", traceId)
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
