package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  具体 Agent 实现（优化版）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  每个 Agent 只实现一个方法：buildUnifiedPrompt
 *  一次 LLM 调用返回：
 *    SPEAK: YES/NO
 *    CONTENT: 发言内容
 *    VOTE: BULL/BEAR
 *    TAGS: 标签
 *
 *  Attention：每个 Agent 声明 attentionTags
 *  只接收相关消息 → prompt 更短 → token 更省
 */

// ── Analyst（看多分析师）───────────────────────────────────────

class AnalystAgent : Agent {
    override val role = "analyst"
    override val displayName = "分析师"
    override val attentionTags = listOf("BEAR", "RISK", "VALUATION", "GROWTH", "NEWS")
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val count = board.messages.count { it.role == role && it.round == board.round }
        return count < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val relevant = relevantMessages(board)
        val history = relevant.joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }

        return """你是投委会的看多分析师。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

${if (history.isNotBlank()) "相关讨论：\n$history" else "（讨论刚开始）"}

请判断你是否需要发言，如果需要则直接给出分析。

输出格式（严格遵守）：
SPEAK: YES 或 NO
${if (true) "CONTENT: （如果SPEAK为YES）你的看多分析，包括基本面/技术面/估值/催化剂，200字以内" else ""}
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔的标签（如 BULL,VALUATION,GROWTH）"""
    }
}

// ── RiskAgent（看空风险官）───────────────────────────────────────

class RiskAgent : Agent {
    override val role = "risk_officer"
    override val displayName = "风险官"
    override val attentionTags = listOf("BULL", "GROWTH", "VALUATION", "NEWS")
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val count = board.messages.count { it.role == role && it.round == board.round }
        return count < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val relevant = relevantMessages(board)
        val history = relevant.joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }

        return """你是投委会的风险官（看空方）。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

${if (history.isNotBlank()) "相关讨论：\n$history" else "（讨论刚开始）"}

请判断你是否需要发言，如果需要则直接给出风险分析。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的风险分析，包括财务风险/行业风险/技术面风险/宏观风险，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔的标签（如 BEAR,RISK）"""
    }
}

// ── Strategist（中立策略师）───────────────────────────────────────

class StrategistAgent : Agent {
    override val role = "strategy_validator"
    override val displayName = "策略师"
    override val attentionTags = listOf("BULL", "BEAR", "RISK", "GROWTH", "VALUATION")
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val count = board.messages.count { it.role == role && it.round == board.round }
        return count < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val relevant = relevantMessages(board)
        val history = relevant.joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }

        return """你是投委会的策略师（中立）。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

${if (history.isNotBlank()) "相关讨论：\n$history" else "（讨论刚开始）"}

请判断你是否需要发言（通常在多空双方都有发言后你才需要），如果需要则给出策略建议。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的策略建议，包括多空权衡/入场出场价位/风险收益比/仓位建议，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔的标签（如 STRATEGY,VALUATION）"""
    }
}

// ── IntelAgent（情报官）───────────────────────────────────────

class IntelAgent : Agent {
    override val role = "intel"
    override val displayName = "情报官"
    override val attentionTags = listOf("NEWS", "GENERAL")
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val totalSpeeches = board.messages.count { it.role == role }
        return totalSpeeches < 3
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val relevant = relevantMessages(board)
        val history = relevant.takeLast(3).joinToString("\n") { "[${it.role}] ${it.content.take(100)}" }

        return """你是投委会的情报官。标的：${board.subject}
轮次：${board.round}

${if (history.isNotBlank()) "当前讨论：\n$history" else "（讨论刚开始）"}

请判断是否需要补充市场情报。只在缺少基础情报或有重大新信息时发言。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）${board.subject}的最新市场情报，包括价格/成交量/新闻/行业动态，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔的标签（如 NEWS,GROWTH）"""
    }
}

// ── ExecutorAgent（执行官）───────────────────────────────────────

class ExecutorAgent : Agent {
    override val role = "executor"
    override val displayName = "执行员"
    override val attentionTags = listOf("STRATEGY", "EXECUTION", "BULL", "BEAR")
    override val canVote = false

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val hasRating = board.finalRating != null
        val hasConsensus = board.consensus
        return (hasRating || hasConsensus) && board.executionPlan == null
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val rating = board.finalRating ?: "Hold"
        val relevant = relevantMessages(board)
        val history = relevant.takeLast(4).joinToString("\n") { "[${it.role}] ${it.content.take(100)}" }

        return """你是投委会的执行官。标的：${board.subject}
最终评级：$rating

${if (history.isNotBlank()) "相关讨论：\n$history" else ""}

请判断是否需要制定执行计划（通常在有明确评级后才需要）。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）具体执行方案：操作方向/仓位/入场价位/止损止盈/分批策略
TAGS: EXECUTION,STRATEGY"""
    }
}
