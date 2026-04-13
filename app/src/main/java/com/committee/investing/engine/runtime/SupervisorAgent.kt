package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SupervisorAgent — 主席/裁判（v4）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v4：使用 board.summary 构建 prompt（summary aware）
 */
class SupervisorAgent : SupervisorCapability {

    override val role = "supervisor"
    override val displayName = "主席"
    override val attentionTags = emptyList<MsgTag>()
    override val canVote = false

    override fun eligible(board: Blackboard): Boolean = !board.finished

    override fun buildUnifiedPrompt(board: Blackboard): String = ""

    // ── 结束判断 ────────────────────────────────────────────

    override fun buildFinishPrompt(board: Blackboard): String {
        val debateCount = board.messages.count { it.role != "supervisor" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "投票：${bull}看多 / ${bear}看空"
        } else "尚无投票"

        val recentMsgs = if (board.summary.isNotBlank()) {
            "摘要：${board.summary}"
        } else {
            board.messages.takeLast(4).joinToString("\n") { "[${it.role}] ${it.content.take(80)}" }
        }

        return """你是投委会主席。判断讨论是否充分可以评级。

标的：${board.subject} | 轮次：${board.round} | 发言：${debateCount}条 | $voteSummary

$recentMsgs

只回答：YES（可以评级）或 NO（继续讨论）"""
    }

    // ── 监督点评 ────────────────────────────────────────────

    override fun buildSupervisionPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(120)}" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "\n投票：${bull}看多 / ${bear}看空"
        } else ""

        return """你是投委会主席。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}$voteSummary

${if (board.summary.isNotBlank()) "讨论摘要：${board.summary}\n" else ""}
近期发言：
$recent

简短点评讨论状态和方向。100字以内。"""
    }

    // ── 最终评级 ────────────────────────────────────────────

    override fun buildRatingPrompt(board: Blackboard): String {
        val history = if (board.summary.isNotBlank()) {
            "讨论摘要：${board.summary}\n\n近期发言：\n" +
                    board.messages.takeLast(8).joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(150)}" }
        } else {
            board.messages.joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(200)}" }
        }
        val votes = board.votes.joinToString("\n") { "[${it.role}] ${if (it.agree) "看多" else "看空"}" }

        return """你是投委会主席，给出最终评级。
标的：${board.subject} | ${board.round}轮讨论 | ${board.messages.size}条发言

$history

${if (votes.isNotBlank()) "投票：\n$votes" else ""}

格式：【最终评级】Buy/Overweight/Hold+/Hold/Underweight/Sell
然后评级理由，200字以内。"""
    }

    // ── Summary 生成 prompt ─────────────────────────────────

    fun buildSummaryPrompt(board: Blackboard): String {
        val allMessages = board.messages.joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(120)}" }
        val existingSummary = if (board.summary.isNotBlank()) "\n\n前次摘要：${board.summary}" else ""
        return """总结当前投委会讨论。

标的：${board.subject}
轮次：${board.round}
$existingSummary

全部发言：
$allMessages

请输出结构化摘要：
【多头观点】（核心论点，每条一句话）
【空头观点】（核心论点，每条一句话）
【当前分歧】（未解决的关键分歧）
【已达成共识】（双方一致的观点）
200字以内。"""
    }
}
