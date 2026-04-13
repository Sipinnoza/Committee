package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  具体 Agent（v4）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  变化：
 *    1. attentionTags 用 MsgTag 枚举
 *    2. buildUnifiedPrompt 使用 board.contextForAgent(this)
 *       自动拼 summary + relevant messages
 */

class AnalystAgent : Agent {
    override val role = "analyst"
    override val displayName = "分析师"
    override val attentionTags = listOf(MsgTag.BEAR, MsgTag.RISK, MsgTag.VALUATION, MsgTag.GROWTH, MsgTag.NEWS)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val mustSpeak = board.messages.isEmpty()
        val instruction = if (mustSpeak) {
            "这是第一轮，你必须发言。直接给出你的看多分析。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有讨论进展，请根据最新信息判断是否需要补充看多观点。如果有人提出看空理由，你应当反驳。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是投委会的看多分析师。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的看多分析，包括基本面/技术面/估值/催化剂，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 BULL,VALUATION,GROWTH）"""
    }
}

class RiskAgent : Agent {
    override val role = "risk_officer"
    override val displayName = "风险官"
    override val attentionTags = listOf(MsgTag.BULL, MsgTag.GROWTH, MsgTag.VALUATION, MsgTag.NEWS, MsgTag.TECHNICAL)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val mustSpeak = board.messages.isEmpty()
        val instruction = if (mustSpeak) {
            "这是第一轮，你必须发言。直接给出你的风险分析。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有讨论进展，请根据最新信息判断是否需要补充风险观点。如果有人提出看多理由，你应当指出风险。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是投委会的风险官（看空方）。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的风险分析，包括财务风险/行业风险/技术面风险/宏观风险，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 BEAR,RISK）"""
    }
}

class StrategistAgent : Agent {
    override val role = "strategy_validator"
    override val displayName = "策略师"
    override val attentionTags = listOf(MsgTag.BULL, MsgTag.BEAR, MsgTag.RISK, MsgTag.GROWTH, MsgTag.VALUATION)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val hasDebate = board.messages.count { it.role != "supervisor" } >= 2
        val instruction = if (!hasDebate) {
            "多空双方尚未充分发言，你暂时不需要发言。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有充分讨论，请基于多空观点给出策略建议。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是投委会的策略师（中立）。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的策略建议，包括多空权衡/入场出场价位/风险收益比/仓位建议，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 STRATEGY,VALUATION）"""
    }
}

class IntelAgent : Agent {
    override val role = "intel"
    override val displayName = "情报官"
    override val attentionTags = listOf(MsgTag.NEWS, MsgTag.GENERAL, MsgTag.TECHNICAL)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role } < 3
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val hasIntel = board.messages.any { it.role == role }
        val instruction = if (!hasIntel) {
            "这是首次讨论，你必须发言。提供该标的的市场情报。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "请判断是否有重大新信息需要补充。"
        } else {
            "请判断是否需要补充市场情报。"
        }
        return """你是投委会的情报官。标的：${board.subject}
轮次：${board.round}

$context

$instruction

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）${board.subject}的市场情报，包括价格/成交量/新闻/行业动态，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 NEWS,GROWTH）"""
    }
}

class ExecutorAgent : Agent {
    override val role = "executor"
    override val displayName = "执行员"
    override val attentionTags = listOf(MsgTag.STRATEGY, MsgTag.EXECUTION, MsgTag.BULL, MsgTag.BEAR)
    override val canVote = false

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val hasRating = board.finalRating != null
        val hasConsensus = board.consensus
        return (hasRating || hasConsensus) && board.executionPlan == null
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val rating = board.finalRating ?: "Hold"
        val context = board.contextForAgent(this)
        return """你是投委会的执行官。标的：${board.subject}
最终评级：$rating

$context

请判断是否需要制定执行计划（通常在有明确评级后才需要）。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）具体执行方案：操作方向/仓位/入场价位/止损止盈/分批策略
TAGS: EXECUTION,STRATEGY"""
    }
}
