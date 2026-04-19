package com.znliang.committee.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Agent — 自主决策单元（v4）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v4 新增：
 *    1. contextForAgent(board) —— 自动拼 summary + relevant messages
 *    2. attentionTags 用 MsgTag 枚举（不再用 String）
 *    3. scoring() —— 加权评分（替代随机选择）
 */

typealias QuickLlm = suspend (prompt: String) -> String

interface Agent {
    val role: String
    val displayName: String

    /** 🔥 Attention 声明（标准化枚举） */
    val attentionTags: List<MsgTag>
        get() = emptyList()

    val canVote: Boolean
        get() = true

    /** 基本资格过滤（纯计算） */
    fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        return spokenThisRound < 2
    }

    /**
     * 🔥 统一入口：一次 LLM 返回 SPEAK + CONTENT + VOTE + TAGS
     */
    suspend fun respond(board: Blackboard, llm: QuickLlm): UnifiedResponse {
        val prompt = buildUnifiedPrompt(board)
        if (prompt.isBlank()) return UnifiedResponse(false, "", null, emptyList(), emptyList())
        val raw = llm(prompt)
        return UnifiedResponse.parse(raw, canVote)
    }

    /** 构建统一 prompt */
    fun buildUnifiedPrompt(board: Blackboard): String

    /** 🔥 Attention：按标准化标签过滤 */
    fun relevantMessages(board: Blackboard): List<BoardMessage> {
        return if (attentionTags.isEmpty()) board.messages.takeLast(8)
        else board.messagesByTags(*attentionTags.toTypedArray()).takeLast(6)
    }

    /**
     * 🔥 L3-2：加权评分（替代 random）
     *
     * 评分维度：
     *   + 没发言过 / 很久没发言    → 该说
     *   + 讨论内容与角色相关        → 该说
     *   + 当前有分歧涉及该角色      → 该说
     *   + 已经连续发言              → 降分
     */
    fun scoring(board: Blackboard): Double {
        var score = 0.0

        // ① 轮间未发言轮数（越久没说越该说）
        val lastSpokeRound = board.messages.lastOrNull { it.role == role }?.round ?: 0
        val roundsSinceLastSpoke = board.round - lastSpokeRound
        score += minOf(roundsSinceLastSpoke, 5) * 1.0

        // ② 最近消息与 attentionTags 的匹配度
        val recentMessages = board.messages.takeLast(4)
        val relevantCount = recentMessages.count { msg ->
            msg.normalizedTags.any { it in attentionTags }
        }
        score += relevantCount * 2.0

        // ③ 当前有分歧（看多看空票数接近）→ 策略师/风险官加分
        val bullCount = board.votes.values.count { it.agree }
        val bearCount = board.votes.size - bullCount
        val hasDivergence = board.votes.size >= 2 && kotlin.math.abs(bullCount - bearCount) <= 1
        if (hasDivergence) {
            when (role) {
                "strategy_validator" -> score += 3.0
                "risk_officer" -> score += 2.0
                "analyst" -> score += 2.0
            }
        }

        // ④ 本轮已发言 → 降分
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        score -= spokenThisRound * 3.0

        // ⑤ 第一轮 → Intel/Analyst 加分
        if (board.round == 1) {
            when (role) {
                "intel" -> score += 3.0
                "analyst" -> score += 2.0
            }
        }

        return score
    }
}

/**
 * Supervisor 扩展接口
 */
interface SupervisorCapability : Agent {
    override val canVote: Boolean
        get() = false

    fun buildFinishPrompt(board: Blackboard): String
    fun buildSupervisionPrompt(board: Blackboard): String
    fun buildRatingPrompt(board: Blackboard): String
}
