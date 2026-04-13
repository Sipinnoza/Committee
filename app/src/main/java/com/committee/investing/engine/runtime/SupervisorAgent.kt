package com.committee.investing.engine.runtime

import android.util.Log

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SupervisorAgent — 主席 / 裁判
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  关键变化：
 *    ❌ 旧：PRIORITY 排序决定谁发言（伪调度）
 *    ✅ 新：不排班，只裁判。Agent 自己通过 shouldAct 决定发言
 *
 *  Supervisor 的职责：
 *    1. 每轮观察讨论，必要时点评（act → LLM）
 *    2. 判断讨论是否充分（shouldFinish → LLM）
 *    3. 做最终评级（buildRatingPrompt → LLM）
 *
 *  Supervisor 不决定谁发言。那是 Agent 自己的事。
 */
class SupervisorAgent : SupervisorCapability {

    companion object {
        private const val TAG = "SupervisorAgent"
    }

    override val role = "supervisor"
    override val displayName = "主席"

    // ── eligible：Supervisor 始终有资格 ─────────────────────────

    override fun eligible(board: Blackboard): Boolean = !board.finished

    // ── shouldAct：Supervisor 每轮都要评估 ──────────────────────

    override fun buildShouldActPrompt(board: Blackboard): String {
        // Supervisor 总是需要评估，不需要问 LLM
        // 返回空字符串 → shouldAct 基类返回 false
        // 我们直接在 AgentRuntime 中特殊处理 supervisor
        return ""
    }

    override suspend fun shouldAct(board: Blackboard, llm: QuickLlm): Boolean {
        // Supervisor 不走 shouldAct 流程，由 Runtime 直接安排
        return false
    }

    // ── act：监督点评 或 评级 ────────────────────────────────────

    override fun act(board: Blackboard): AgentDecision {
        // Supervisor 在轮间做点评
        return AgentDecision(
            action = AgentAction.Speak(""),
            needsLlm = true,
            prompt = buildSupervisionPrompt(board),
        )
    }

    // ── shouldFinish：🔥 LLM 驱动判断 ──────────────────────────

    override fun buildFinishPrompt(board: Blackboard): String {
        val debateCount = board.messages.count { it.role != "supervisor" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "投票情况：${bull}票看多 / ${bear}票看空"
        } else {
            "尚无投票"
        }
        val recentMsgs = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(100)}" }

        return """你是投委会主席。请判断讨论是否已经充分，可以做出最终评级。

标的：${board.subject}
轮次：${board.round}
发言数：${debateCount}条
$voteSummary

近期讨论：
$recentMsgs

判断标准：
- 多空双方是否都已充分表达？
- 是否有足够信息做出评级？
- 继续讨论是否会产生新价值？

只回答：YES（可以评级）或 NO（需要继续讨论）"""
    }

    // ── 监督点评 prompt ────────────────────────────────────────

    override fun buildSupervisionPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(10).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val bull = board.votes.count { it.agree }
            val bear = board.votes.size - bull
            "\n当前投票：${bull}看多 / ${bear}看空"
        } else ""

        return """你是投资委员会的主席。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}$voteSummary

你的职责是监督讨论，确保讨论质量和方向。
已有讨论：
$history

请简短点评当前讨论状态：
1. 各方论点是否充分？
2. 还有哪些方面需要补充？
3. 讨论方向是否偏离？

100字以内。"""
    }

    // ── 最终评级 prompt ────────────────────────────────────────

    override fun buildRatingPrompt(board: Blackboard): String {
        val history = board.messages.joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(300)}" }
        val votes = board.votes.joinToString("\n") { "[${it.role}] ${if (it.agree) "看多" else "看空"}: ${it.reason}" }

        return """你是投资委员会的主席，现在需要给出最终投资评级。
当前标的：${board.subject}
共 ${board.round} 轮讨论，${board.messages.size} 条发言。

全部讨论记录：
$history

${if (votes.isNotBlank()) "投票记录：\n$votes" else ""}

请基于以上讨论，给出最终评级。格式必须是：

【最终评级】Buy/Overweight/Hold+/Hold/Underweight/Sell

然后给出评级理由和关键考量因素，200字以内。"""
    }

    // ── 投票 prompt（Runtime 调用） ────────────────────────────

    fun buildVotePrompt(board: Blackboard): String {
        return """基于当前讨论，你对${board.subject}的投资立场是？

A. 看多（BULLISH）
B. 看空（BEARISH）

只回答 A 或 B。"""
    }
}
