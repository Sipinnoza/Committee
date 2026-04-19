package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  ExecutorSelfEvolver — 执行员专属自我进化引擎
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  Layer 1 — 执行偏差记忆 (Memory)
 *    - 追踪执行计划与实际结果的偏差
 *    - 记住证伪条件是否足够精确和可操作
 *    - 按标的类型分类的常见执行偏差模式
 *
 *  Layer 2 — 执行策略技能 (Skill)
 *    - 归纳在不同市场环境下的最佳执行方案
 *    - 分批策略/止损策略/仓位管理的历史有效性
 *
 *  Layer 3 — 执行质量自评 + Prompt 进化
 *    - 评估证伪条件是否明确且可验证
 *    - 检测执行方案是否遗漏关键风险场景
 */
class ExecutorSelfEvolver(
    systemLlm: SystemLlmService,
    evolutionRepo: EvolutionRepository,
) : AgentSelfEvolver(systemLlm, evolutionRepo) {

    override fun roleName() = "executor"
    override fun displayName() = "执行员"

    override suspend fun reflect(
        board: Blackboard,
        meetingTraceId: String,
    ): AgentReflection {
        val myMessages = board.messages.filter { it.role == "executor" }
        if (myMessages.isEmpty()) return AgentReflection.empty()

        val mySpeeches = myMessages.joinToString("\n\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(500)}"
        }

        val fullDiscussion = board.messages
            .filter { it.role != "executor" && it.role != "supervisor" }
            .joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }

        val outcome = buildString {
            append("最终评级: ${board.finalRating ?: "无"}\n")
            append("共识: ${if (board.consensus) "是" else "否"}\n")
        }

        val reflectPrompt = """你是执行员的自我进化系统。

## 你在会议中制定的执行计划
$mySpeeches

## 完整讨论上下文
$fullDiscussion

## 会议结果
$outcome

## 任务
从执行员的角度反思你的执行计划质量：

1. 操作方向：你的操作方向是否与最终共识一致？
2. 仓位建议：仓位大小是否合理？是否考虑了波动率和流动性？
3. 入场策略：入场价位和分批策略是否具体且可执行？
4. 止损止盈：止损/止盈设置是否覆盖了讨论中提到的关键风险位？
5. 证伪条件：你的F1-F4证伪条件是否足够精确和可验证？
6. 回顾触发：REVIEW TRIGGER是否合理设定了复查时机？

输出格式（严格遵守）：
EXECUTION_QUALITY: 1-10 — （一句话说明执行方案质量）
POSITION_LESSON: 仓位管理的经验教训
ENTRY_PRECISION: 入场策略的精确度评估和改进
STOP_LOSS_COVERAGE: 止损是否覆盖了关键风险位
FALSIFICATION_QUALITY: 证伪条件的精确度评估
STRATEGY_TIP: 下次同类标的的执行方案模板
PROMPT_FIX: 对你的 system prompt 的具体改进建议（如无需改进则写"无需修改"）
PRIORITY: HIGH / MEDIUM / LOW"""

        val raw = systemLlm.quickCall(reflectPrompt)
        if (raw.isBlank()) return AgentReflection.empty()
        return parseExecutorReflection(raw, meetingTraceId)
    }

    private suspend fun parseExecutorReflection(raw: String, traceId: String): AgentReflection {
        var summary = ""
        var suggestion = ""
        var priority = "MEDIUM"

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("EXECUTION_QUALITY:", ignoreCase = true) ->
                    summary = t.substringAfter(":").trim()
                t.startsWith("POSITION_LESSON:", ignoreCase = true) -> {
                    val lesson = t.substringAfter(":").trim()
                    if (lesson.isNotBlank()) {
                        saveExperience("INSIGHT", "仓位经验: $lesson", traceId = traceId)
                    }
                }
                t.startsWith("STOP_LOSS_COVERAGE:", ignoreCase = true) -> {
                    val coverage = t.substringAfter(":").trim()
                    if (coverage.isNotBlank()) {
                        saveExperience("INSIGHT", "止损覆盖: $coverage", traceId = traceId)
                    }
                }
                t.startsWith("FALSIFICATION_QUALITY:", ignoreCase = true) -> {
                    val qual = t.substringAfter(":").trim()
                    if (qual.isNotBlank()) {
                        saveExperience("STRATEGY", "证伪条件: $qual", traceId = traceId)
                    }
                }
                t.startsWith("STRATEGY_TIP:", ignoreCase = true) -> {
                    val tip = t.substringAfter(":").trim()
                    if (tip.isNotBlank()) {
                        saveExperience("STRATEGY", "执行模板: $tip", "POSITIVE", "MEDIUM", traceId)
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
