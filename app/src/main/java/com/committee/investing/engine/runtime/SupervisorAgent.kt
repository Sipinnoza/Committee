package com.committee.investing.engine.runtime

import android.util.Log

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SupervisorAgent — 主席 + 调度器
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  这是整个系统的核心：
 *
 *  旧架构：CommitteeLooper（中央控制器）→ Scheduler → AgentPool
 *  新架构：SupervisorAgent（智能调度器）→ AgentRuntime → AgentPool
 *
 *  关键变化：
 *    ❌ 谁发言 = 查表（PhaseDef.agents 顺序）
 *    ✅ 谁发言 = Supervisor 根据 Blackboard 自主判断
 *
 *  调度策略：
 *    Round 1: intel → analyst → risk_officer → supervisor 评估
 *    Round 2+: analyst → risk_officer → strategist → supervisor 评估
 *    共识/最大轮次: supervisor 评级 → executor 执行
 *
 *  但这些"策略"不是硬编码的流程，而是 Supervisor 基于 Blackboard 的判断：
 *    - shouldAct() = 每轮必须执行（supervisor 总是 true）
 *    - decideNextAgent() = 看谁还没说 + 谁有资格说
 *    - shouldFinish() = 看共识/轮次/讨论质量
 */
class SupervisorAgent : SupervisorCapability {

    companion object {
        private const val TAG = "SupervisorAgent"

        /** Agent 优先级：数字越小越优先 */
        private val PRIORITY = mapOf(
            "intel" to 0,
            "analyst" to 1,
            "risk_officer" to 2,
            "strategist" to 3,
        )

        /** 每轮期望发言的 agent 列表（按优先级） */
        private val DEBATE_AGENTS = listOf("analyst", "risk_officer", "strategist")
        private val ROUND1_EXTRA = listOf("intel")
    }

    override val role = "supervisor"
    override val displayName = "主席"

    // ── Agent 接口实现 ─────────────────────────────────────────────

    override fun shouldAct(board: Blackboard): Boolean {
        // Supervisor 每轮必须执行（除非已结束）
        return !board.finished
    }

    override fun act(board: Blackboard): AgentDecision {
        // Supervisor 的 act 分两种：
        // 1. 每轮结束时的监督评估（不需要 LLM）
        // 2. 最终评级（需要 LLM）

        return if (shouldFinish(board)) {
            // 需要做最终评级
            val prompt = buildRatingPrompt(board)
            AgentDecision(
                action = AgentAction.Finish(rating = null),
                needsLlm = true,
                prompt = prompt,
            )
        } else {
            // 每轮监督评估
            AgentDecision(
                action = AgentAction.Speak(""),
                needsLlm = shouldSupervisorComment(board),
                prompt = buildSupervisionPrompt(board),
            )
        }
    }

    // ── SupervisorAgent 调度接口 ───────────────────────────────────

    override fun decideNextAgent(agents: List<Agent>, board: Blackboard): Agent? {
        // 策略：找出所有 shouldAct == true 的 Agent，按优先级选一个

        val candidates = agents
            .filter { it.shouldAct(board) }
            .sortedBy { PRIORITY[it.role] ?: 99 }

        if (candidates.isEmpty()) {
            Log.e(TAG, "[decideNextAgent] 没有候选 agent，本轮结束")
            return null
        }

        val chosen = candidates.first()
        Log.e(TAG, "[decideNextAgent] round=${board.round} 候选=${candidates.map { it.role }} → 选中=${chosen.role}")
        return chosen
    }

    override fun shouldFinish(board: Blackboard): Boolean {
        // 结束条件（任一满足即结束）
        val reasons = mutableListOf<String>()

        if (board.consensus) reasons += "共识已达成"
        if (board.round > board.maxRounds) reasons += "超过最大轮次 ${board.maxRounds}"
        if (board.finalRating != null) reasons += "已有评级"

        // 检查连续 Pass：如果最近一轮所有人都没新发言
        val lastRoundMessages = board.messages.count { it.round == board.round }
        if (board.round > 1 && lastRoundMessages == 0) reasons += "本轮无人发言"

        if (reasons.isNotEmpty()) {
            Log.e(TAG, "[shouldFinish] true: ${reasons.joinToString(", ")}")
            return true
        }
        return false
    }

    // ── 内部逻辑 ───────────────────────────────────────────────────

    /** Supervisor 是否需要发言（不是每轮都需要） */
    private fun shouldSupervisorComment(board: Blackboard): Boolean {
        // 第一轮末：需要做开场总结
        // 后续轮：看讨论是否有分歧需要仲裁
        val debateMessages = board.messages.filter { it.role in DEBATE_AGENTS }
        return debateMessages.size >= 2 && board.messages.none { it.role == role && it.round == board.round }
    }

    private fun buildSupervisionPrompt(board: Blackboard): String {
        val history = board.messages.takeLast(10).joinToString("\n") { "[${it.role}] ${it.content.take(200)}" }
        return """你是投资委员会的主席。
当前标的：${board.subject}
当前轮次：${board.round}/${board.maxRounds}

你的职责是监督讨论，确保讨论质量和方向。
已有讨论：
$history

请简短点评当前讨论状态，指出需要补充的方向。100字以内。

如果各方观点已经趋于一致，请明确说"【共识达成】"。
如果讨论已经充分，需要做出评级，请说"【请求评级】"。"""
    }

    private fun buildRatingPrompt(board: Blackboard): String {
        val history = board.messages.joinToString("\n") { "[${it.role}] R${it.round}: ${it.content.take(300)}" }
        val votes = board.votes.joinToString("\n") { "[${it.role}] ${if (it.agree) "看多" else "看空"}: ${it.reason}" }

        return """你是投资委员会的主席，现在需要给出最终投资评级。
当前标的：${board.subject}

全部讨论记录：
$history

${if (votes.isNotBlank()) "投票记录：\n$votes" else ""}

请基于以上讨论，给出最终评级。格式必须是：

【最终评级】Buy/Overweight/Hold+/Hold/Underweight/Sell

然后给出评级理由和关键考量因素，200字以内。"""
    }
}
