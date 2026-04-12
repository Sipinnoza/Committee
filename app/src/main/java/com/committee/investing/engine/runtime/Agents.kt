package com.committee.investing.engine.runtime

import com.committee.investing.domain.model.AgentRole

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  具体 Agent 实现
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  每个 Agent 的行为规则：
 *
 *  Analyst   — 分析师（看多）
 *    shouldAct: 本轮未发言时 → true
 *    act: 对标的做基本面分析
 *
 *  RiskAgent — 风险官（看空）
 *    shouldAct: Analyst 发言后才行动（本轮有 analyst 消息 → true）
 *    act: 提出风险因素和反对意见
 *
 *  Strategist — 策略师（中立）
 *    shouldAct: 有 Analyst + Risk 发言后 → true
 *    act: 综合分析，提出策略建议
 *
 *  IntelAgent — 情报官
 *    shouldAct: 本轮未发言时 → true（和分析师并行）
 *    act: 提供市场情报和数据支撑
 *
 *  ExecutorAgent — 执行官
 *    shouldAct: 共识达成或 supervisor 评级后 → true
 *    act: 制定执行计划
 *
 *  SupervisorAgent — 主席/调度器（Task 5 重点）
 *    shouldAct: 每轮必须执行
 *    act: 监督讨论，决定下一步
 *    decideNextAgent: 选择下一个 agent
 *    shouldFinish: 判断是否结束
 */

// ── Analyst ─────────────────────────────────────────────────────

class AnalystAgent : Agent {
    override val role = "analyst"
    override val displayName = "分析师"

    override fun shouldAct(board: Blackboard): Boolean {
        if (board.finished) return false
        // 本轮未发言 → 行动
        return board.messages.none { it.role == role && it.round == board.round }
    }

    override fun act(board: Blackboard): AgentDecision {
        val previousAnalysis = board.messages
            .filter { it.role == role }
            .lastOrNull()?.content ?: ""

        val prompt = buildPrompt(board)
        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = prompt,
        )
    }

    private fun buildPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        return """你是投资委员会的分析师（看多方）。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是分析投资标的的正面因素，包括：
- 基本面分析（营收、利润、增长）
- 技术面信号
- 市场情绪与催化剂
- 估值合理性

${if (history.isNotBlank()) "已有讨论：\n$history" else ""}

请给出你的分析。简洁有力，200字以内。"""
    }
}

// ── RiskAgent ───────────────────────────────────────────────────

class RiskAgent : Agent {
    override val role = "risk_officer"
    override val displayName = "风险官"

    override fun shouldAct(board: Blackboard): Boolean {
        if (board.finished) return false
        // Analyst 发言后才行动（检查本轮或之前有 analyst 消息）
        val hasAnalystInput = board.messages.any { it.role == "analyst" && it.round == board.round }
            || board.messages.any { it.role == "analyst" && it.round >= board.round - 1 }
        // 本轮未发言 + analyst 已发言
        val notSpoken = board.messages.none { it.role == role && it.round == board.round }
        return notSpoken && hasAnalystInput
    }

    override fun act(board: Blackboard): AgentDecision {
        val prompt = buildPrompt(board)
        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = prompt,
        )
    }

    private fun buildPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        return """你是投资委员会的风险官（看空方）。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是指出风险因素，包括：
- 财务风险（负债率、现金流、商誉）
- 行业风险（竞争、监管、周期性）
- 技术面风险信号
- 宏观风险

${if (history.isNotBlank()) "已有讨论：\n$history" else ""}

请给出你的风险分析。简洁有力，200字以内。"""
    }
}

// ── Strategist ──────────────────────────────────────────────────

class StrategistAgent : Agent {
    override val role = "strategist"
    override val displayName = "策略师"

    override fun shouldAct(board: Blackboard): Boolean {
        if (board.finished) return false
        val notSpoken = board.messages.none { it.role == role && it.round == board.round }
        // 有分析师和风险官的发言后才行动
        val hasBoth = board.messages.any { it.role == "analyst" }
            && board.messages.any { it.role == "risk_officer" }
        return notSpoken && hasBoth
    }

    override fun act(board: Blackboard): AgentDecision {
        val prompt = buildPrompt(board)
        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = prompt,
        )
    }

    private fun buildPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(8).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        return """你是投资委员会的策略师（中立）。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是综合各方观点，制定投资策略：
- 权衡多空双方的论点
- 提出入场/出场价位建议
- 风险收益比评估
- 仓位管理建议

已有讨论：
$history

请给出你的策略建议。简洁有力，200字以内。"""
    }
}

// ── IntelAgent ──────────────────────────────────────────────────

class IntelAgent : Agent {
    override val role = "intel"
    override val displayName = "情报官"

    override fun shouldAct(board: Blackboard): Boolean {
        if (board.finished) return false
        // 只在第1轮发言（提供初始情报）
        return board.round == 1 && board.messages.none { it.role == role }
    }

    override fun act(board: Blackboard): AgentDecision {
        val prompt = """你是投资委员会的情报官。
当前标的：${board.subject}

你的职责是提供客观的市场情报：
- 最新价格和成交量
- 近期新闻和事件
- 行业动态
- 相关宏观数据

请提供${board.subject}的最新市场情报摘要。简洁客观，200字以内。"""

        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = prompt,
        )
    }
}

// ── ExecutorAgent ───────────────────────────────────────────────

class ExecutorAgent : Agent {
    override val role = "executor"
    override val displayName = "执行官"

    override fun shouldAct(board: Blackboard): Boolean {
        if (board.finished) return false
        // 在评级阶段或共识达成后行动
        val hasRating = board.finalRating != null
        val inExecutionPhase = board.phase == BoardPhase.EXECUTION
        return (hasRating || inExecutionPhase) && board.executionPlan == null
    }

    override fun act(board: Blackboard): AgentDecision {
        val rating = board.finalRating ?: "Hold"
        val prompt = """你是投资委员会的执行官。
当前标的：${board.subject}
最终评级：$rating

基于委员会的讨论结果和最终评级，请制定具体执行计划：
- 操作方向（买入/卖出/持有）
- 建议仓位
- 入场价位和时机
- 止损止盈设置
- 分批执行策略

请给出具体的执行方案。简洁明了。"""

        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = prompt,
        )
    }
}
