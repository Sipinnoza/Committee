package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Blackboard — Agent 共享环境（唯一状态源）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  所有 Agent 只能通过 Blackboard 读写状态
 *  Phase 不再是控制变量，而是从状态中推断出来的标签
 *  Consensus 不再靠字符串匹配，而是靠投票比例
 */

data class Blackboard(
    /** 会议标的 */
    val subject: String = "",
    /** 当前轮次 */
    var round: Int = 1,
    /** 最大轮次（仅作为绝对安全阀） */
    var maxRounds: Int = 20,
    /** 所有发言记录（append-only） */
    val messages: MutableList<BoardMessage> = mutableListOf(),
    /** 投票记录 */
    val votes: MutableList<BoardVote> = mutableListOf(),
    /** 当前阶段（从状态推断，不手动设置） */
    var phase: BoardPhase = BoardPhase.IDLE,
    /** 是否达成共识（从投票比例计算） */
    var consensus: Boolean = false,
    /** 是否结束 */
    var finished: Boolean = false,
    /** 最终评级（supervisor 填写） */
    var finalRating: String? = null,
    /** 执行计划（executor 填写） */
    var executionPlan: String? = null,
) {
    /** 🔥 共识比例：看多票 / 总票数 */
    fun bullRatio(): Float {
        if (votes.isEmpty()) return 0f
        val bull = votes.count { it.agree }
        return bull.toFloat() / votes.size
    }

    /** 🔥 共识比例：看空票 / 总票数 */
    fun bearRatio(): Float {
        if (votes.isEmpty()) return 0f
        return 1f - bullRatio()
    }

    /** 🔥 是否达成共识（任一方 > 70%） */
    fun hasConsensus(): Boolean {
        return votes.size >= 2 && (bullRatio() > 0.7f || bearRatio() > 0.7f)
    }

    /** 🔥 从当前状态推断阶段（Phase 是标签，不是控制器） */
    fun inferPhase(): BoardPhase = when {
        finished && executionPlan != null -> BoardPhase.DONE
        finished && finalRating != null -> BoardPhase.EXECUTION
        finalRating != null -> BoardPhase.RATING
        votes.isNotEmpty() && votes.size >= 2 -> BoardPhase.VOTE
        round > 1 || messages.size > 3 -> BoardPhase.DEBATE
        messages.isEmpty() -> BoardPhase.IDLE
        else -> BoardPhase.ANALYSIS
    }
}

/** 消息 */
data class BoardMessage(
    val role: String,
    val content: String,
    val round: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

/** 投票 */
data class BoardVote(
    val role: String,
    val agree: Boolean,     // true = 看多, false = 看空
    val reason: String = "",
    val round: Int,
)

/** 阶段 — 标签，不控制流程 */
enum class BoardPhase {
    IDLE,
    ANALYSIS,
    DEBATE,
    VOTE,
    RATING,
    EXECUTION,
    DONE,
}

/** Agent 行为输出 */
sealed class AgentAction {
    data class Speak(val content: String) : AgentAction()
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
