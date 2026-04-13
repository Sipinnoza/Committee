package com.committee.investing.engine.runtime

import android.util.Log

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SupervisorAgent — 主席 / 裁判（优化版）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  不排班，不调度，不投票
 *  只做三件事：
 *    1. 点评（低频）
 *    2. 判断是否结束（低频）
 *    3. 最终评级
 */
class SupervisorAgent : SupervisorCapability {

    companion object {
        private const val TAG = "SupervisorAgent"
    }

    override val role = "supervisor"
    override val displayName = "主席"
    override val attentionTags = emptyList<String>()
    override val canVote = false

    override fun eligible(board: Blackboard): Boolean = !board.finished

    // Supervisor 不走 respond 流程，由 Runtime 直接安排
    override fun buildUnifiedPrompt(board: Blackboard): String = ""

    // ── 结束判断 ────────────────────────────────────────────

    override fun buildFinishPrompt(board: Blackboard): String {
        val debateCount = board.messages.count { it.role != "supervisor" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "投票：${bull}看多 / ${bear}看空"
        } else {
            "尚无投票"
        }
        val recentMsgs = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(80)}" }

        return """你是投委会主席。判断讨论是否充分可以评级。

标的：${board.subject} | 轮次：${board.round} | 发言：${debateCount}条 | $voteSummary

近期讨论：
$recentMsgs

只回答：YES（可以评级）或 NO（继续讨论）"""
    }

    // ── 监督点评 ────────────────────────────────────────────

    override fun buildSupervisionPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(8).joinToString("\n") { "[${it.role}] ${it.content.take(150)}" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "\n投票：${bull}看多 / ${bear}看空"
        } else ""

        return """你是投委会主席。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}$voteSummary

已有讨论：
$history

简短点评讨论状态和方向。100字以内。"""
    }

    // ── 最终评级 ────────────────────────────────────────────

    override fun buildRatingPrompt(board: Blackboard): String {
        val history = board.messages.joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(200)}" }
        val votes = board.votes.joinToString("\n") { "[${it.role}] ${if (it.agree) "看多" else "看空"}" }

        return """你是投委会主席，给出最终评级。
标的：${board.subject} | ${board.round}轮讨论 | ${board.messages.size}条发言

讨论记录：
$history

${if (votes.isNotBlank()) "投票：\n$votes" else ""}

格式：【最终评级】Buy/Overweight/Hold+/Hold/Underweight/Sell
然后评级理由，200字以内。"""
    }

    // ── 投票 prompt（给 Runtime 用） ────────────────────────

    fun buildVotePrompt(board: Blackboard): String {
        return "基于当前讨论，你对${board.subject}的立场？\n只回答：BULL 或 BEAR"
    }
}
