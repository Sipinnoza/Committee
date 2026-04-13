package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Blackboard — Agent 共享环境（唯一状态源）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  优化：消息带标签 → Agent 只关注相关消息 → context ↓ token ↓
 */

data class Blackboard(
    val subject: String = "",
    var round: Int = 1,
    var maxRounds: Int = 20,
    val messages: MutableList<BoardMessage> = mutableListOf(),
    val votes: MutableList<BoardVote> = mutableListOf(),
    var phase: BoardPhase = BoardPhase.IDLE,
    var consensus: Boolean = false,
    var finished: Boolean = false,
    var finalRating: String? = null,
    var executionPlan: String? = null,
) {
    fun bullRatio(): Float {
        if (votes.isEmpty()) return 0f
        return votes.count { it.agree }.toFloat() / votes.size
    }

    fun bearRatio(): Float {
        if (votes.isEmpty()) return 0f
        return 1f - bullRatio()
    }

    fun hasConsensus(): Boolean {
        return votes.size >= 2 && (bullRatio() > 0.7f || bearRatio() > 0.7f)
    }

    fun inferPhase(): BoardPhase = when {
        finished && executionPlan != null -> BoardPhase.DONE
        finished && finalRating != null -> BoardPhase.EXECUTION
        finalRating != null -> BoardPhase.RATING
        votes.isNotEmpty() && votes.size >= 2 -> BoardPhase.VOTE
        round > 1 || messages.size > 3 -> BoardPhase.DEBATE
        messages.isEmpty() -> BoardPhase.IDLE
        else -> BoardPhase.ANALYSIS
    }

    /** 🔥 Attention：返回只含指定标签的消息 */
    fun messagesByTags(vararg tags: String): List<BoardMessage> {
        if (tags.isEmpty()) return messages
        return messages.filter { msg -> tags.any { tag -> tag in msg.tags } }
    }

    /** 🔥 Attention：返回排除指定标签的消息 */
    fun messagesExcludingTags(vararg tags: String): List<BoardMessage> {
        return messages.filter { msg -> tags.none { tag -> tag in msg.tags } }
    }
}

data class BoardMessage(
    val role: String,
    val content: String,
    val round: Int,
    val timestamp: Long = System.currentTimeMillis(),
    /** 消息标签，如 [BULL], [BEAR], [RISK], [VALUATION], [NEWS], [STRATEGY], [EXECUTION] */
    val tags: List<String> = emptyList(),
)

data class BoardVote(
    val role: String,
    val agree: Boolean,
    val reason: String = "",
    val round: Int,
)

enum class BoardPhase {
    IDLE, ANALYSIS, DEBATE, VOTE, RATING, EXECUTION, DONE,
}

/** Agent 行为输出 */
sealed class AgentAction {
    data class Speak(val content: String, val tags: List<String> = emptyList()) : AgentAction()
    data class Vote(val agree: Boolean, val reason: String = "") : AgentAction()
    data object Pass : AgentAction()
    data class Finish(val rating: String? = null) : AgentAction()
}

/** Agent 决策结果 */
data class AgentDecision(
    val action: AgentAction,
    val needsLlm: Boolean = false,
    val prompt: String? = null,
)

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  UnifiedResponse — 合并 shouldAct + act + vote 的统一输出
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  一次 LLM 调用，返回三个字段：
 *    SPEAK: YES/NO
 *    CONTENT: （如果 YES）发言内容
 *    VOTE: BULL/BEAR （投票）
 */
data class UnifiedResponse(
    val wantsToSpeak: Boolean,
    val content: String,
    val voteBull: Boolean?,   // null = 不投票（supervisor/executor）
    val tags: List<String>,
) {
    companion object {
        fun parse(raw: String, canVote: Boolean): UnifiedResponse {
            val lines = raw.trim().lines()
            var speak = false
            var content = ""
            var voteBull: Boolean? = null
            val tags = mutableListOf<String>()
            var inContent = false
            val contentLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("SPEAK:", ignoreCase = true)) {
                    val value = trimmed.substringAfter(":").trim().uppercase()
                    speak = value.startsWith("YES") || value.startsWith("Y")
                    inContent = false
                } else if (trimmed.startsWith("CONTENT:", ignoreCase = true)) {
                    inContent = true
                    val firstLine = trimmed.substringAfter(":").trim()
                    if (firstLine.isNotBlank()) contentLines.add(firstLine)
                } else if (trimmed.startsWith("VOTE:", ignoreCase = true)) {
                    inContent = false
                    if (canVote) {
                        val value = trimmed.substringAfter(":").trim().uppercase()
                        voteBull = value.contains("BULL") || value.startsWith("A") || value.contains("看多")
                    }
                } else if (trimmed.startsWith("TAGS:", ignoreCase = true)) {
                    inContent = false
                    val value = trimmed.substringAfter(":").trim()
                    tags.addAll(value.split(",", " ", "|").map { it.trim().uppercase() }
                        .filter { it.isNotBlank() })
                } else if (inContent && trimmed.isNotBlank()) {
                    contentLines.add(trimmed)
                }
            }

            content = contentLines.joinToString("\n")
            if (tags.isEmpty() && content.isNotBlank()) {
                tags.addAll(inferTags(content))
            }

            return UnifiedResponse(
                wantsToSpeak = speak,
                content = content,
                voteBull = voteBull,
                tags = tags,
            )
        }

        private fun inferTags(content: String): List<String> {
            val result = mutableListOf<String>()
            val c = content.uppercase()
            if (c.contains("估值") || c.contains("PE") || c.contains("EPS")) result.add("VALUATION")
            if (c.contains("风险") || c.contains("下跌") || c.contains("止损")) result.add("RISK")
            if (c.contains("增长") || c.contains("营收") || c.contains("利润")) result.add("GROWTH")
            if (c.contains("新闻") || c.contains("公告") || c.contains("事件")) result.add("NEWS")
            if (c.contains("策略") || c.contains("仓位") || c.contains("入场")) result.add("STRATEGY")
            if (c.contains("执行") || c.contains("操作") || c.contains("买入") || c.contains("卖出")) result.add("EXECUTION")
            if (c.contains("看多") || c.contains("利好") || c.contains("买入机会")) result.add("BULL")
            if (c.contains("看空") || c.contains("利空") || c.contains("回避")) result.add("BEAR")
            return result.ifEmpty { listOf("GENERAL") }
        }
    }
}
