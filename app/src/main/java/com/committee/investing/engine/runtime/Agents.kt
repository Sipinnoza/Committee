package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  具体 Agent 实现
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  每个 Agent 的 shouldAct 不再是硬编码规则，而是问 LLM：
 *    "基于当前讨论，你是否需要发言？"
 *
 *  每个 Agent 的 act 逻辑不变：构建 prompt → 返回 AgentDecision
 */

// ── Analyst（看多分析师）───────────────────────────────────────

class AnalystAgent : Agent {
    override val role = "analyst"
    override val displayName = "分析师"

    override fun buildShouldActPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }
        val alreadySaid = board.messages.count { it.role == role && it.round == board.round }
        if (alreadySaid >= 2) return ""  // 已发言2次，不让LLM浪费token

        return """你是投委会的看多分析师。当前标的：${board.subject}
当前轮次：${board.round}

近期讨论摘要：
${if (recent.isNotBlank()) recent else "（尚无讨论）"}

判断你是否需要发言：
- 是否存在未充分分析的多头论点？
- 是否有新的利好因素值得补充？
- 是否需要反驳看空方的观点？

只回答：YES 或 NO"""
    }

    override fun act(board: Blackboard): AgentDecision {
        val history = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        val prompt = """你是投资委员会的分析师（看多方）。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是分析投资标的的正面因素，包括：
- 基本面分析（营收、利润、增长）
- 技术面信号
- 市场情绪与催化剂
- 估值合理性

${if (history.isNotBlank()) "已有讨论：\n$history" else ""}

请给出你的分析。简洁有力，200字以内。"""

        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = prompt)
    }
}

// ── RiskAgent（看空风险官）───────────────────────────────────────

class RiskAgent : Agent {
    override val role = "risk_officer"
    override val displayName = "风险官"

    override fun buildShouldActPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }
        val alreadySaid = board.messages.count { it.role == role && it.round == board.round }
        if (alreadySaid >= 2) return ""

        return """你是投委会的风险官（看空方）。当前标的：${board.subject}
当前轮次：${board.round}

近期讨论摘要：
${if (recent.isNotBlank()) recent else "（尚无讨论）"}

判断你是否需要发言：
- 是否存在未反驳的多头观点？
- 是否存在重大风险尚未提及？
- 看多方是否忽略了关键负面因素？

只回答：YES 或 NO"""
    }

    override fun act(board: Blackboard): AgentDecision {
        val history = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        val prompt = """你是投资委员会的风险官（看空方）。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是指出风险因素，包括：
- 财务风险（负债率、现金流、商誉）
- 行业风险（竞争、监管、周期性）
- 技术面风险信号
- 宏观风险

${if (history.isNotBlank()) "已有讨论：\n$history" else ""}

请给出你的风险分析。简洁有力，200字以内。"""

        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = prompt)
    }
}

// ── Strategist（中立策略师）───────────────────────────────────────

class StrategistAgent : Agent {
    override val role = "strategy_validator"
    override val displayName = "策略师"

    override fun buildShouldActPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(8).joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }
        val alreadySaid = board.messages.count { it.role == role && it.round == board.round }
        if (alreadySaid >= 2) return ""

        return """你是投委会的策略师（中立）。当前标的：${board.subject}
当前轮次：${board.round}

近期讨论摘要：
${if (recent.isNotBlank()) recent else "（尚无讨论）"}

判断你是否需要发言：
- 多空双方是否已有足够发言供你综合？
- 是否需要你的策略框架来调和分歧？
- 是否有投资策略建议需要提出？

只回答：YES 或 NO"""
    }

    override fun act(board: Blackboard): AgentDecision {
        val history = board.messages.takeLast(8).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        val prompt = """你是投资委员会的策略师（中立）。
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

        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = prompt)
    }
}

// ── IntelAgent（情报官）───────────────────────────────────────

class IntelAgent : Agent {
    override val role = "intel"
    override val displayName = "情报官"

    override fun eligible(board: Blackboard): Boolean {
        // 情报官不参与后续辩论，只在情报不足时补充
        if (board.finished) return false
        val totalSpeeches = board.messages.count { it.role == role }
        return totalSpeeches < 3  // 全场最多补充 3 次
    }

    override fun buildShouldActPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(4).joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }

        return """你是投委会的情报官。当前标的：${board.subject}
当前轮次：${board.round}

近期讨论摘要：
${if (recent.isNotBlank()) recent else "（尚无讨论）"}

判断你是否需要发言：
- 是否缺少基础市场情报（价格、成交量、近期事件）？
- 是否有重大新闻或数据尚未提及？
- 其他成员是否需要更多事实支撑？

只回答：YES 或 NO"""
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

        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = prompt)
    }
}

// ── ExecutorAgent（执行官）───────────────────────────────────────

class ExecutorAgent : Agent {
    override val role = "executor"
    override val displayName = "执行员"

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        // 执行官只在有评级或共识后才有资格
        val hasRating = board.finalRating != null
        val hasConsensus = board.consensus
        return (hasRating || hasConsensus) && board.executionPlan == null
    }

    override fun buildShouldActPrompt(board: Blackboard): String {
        // 执行官的 eligible 已经很严格了，到这里基本一定要发言
        return """你是投委会的执行官。当前标的：${board.subject}
最终评级：${board.finalRating ?: "待定"}
共识状态：${if (board.consensus) "已达成" else "未达成"}

是否已有明确的评级需要制定执行计划？
只回答：YES 或 NO"""
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

        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = prompt)
    }
}
