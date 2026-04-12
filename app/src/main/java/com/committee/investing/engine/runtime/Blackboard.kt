package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Blackboard — Agent 共享环境（唯一状态源）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  所有 Agent 只能通过 Blackboard 读写状态
 *  不允许使用全局变量或分散状态
 *
 *  设计原则：
 *    - Agent 不知道其他 Agent 的存在
 *    - Agent 只看 Blackboard 上的信息决定行为
 *    - Blackboard 是 append-only 的消息流（可回放）
 */

data class Blackboard(
    /** 会议标的 */
    val subject: String = "",
    /** 当前轮次 */
    var round: Int = 1,
    /** 最大轮次 */
    var maxRounds: Int = 6,
    /** 所有发言记录（append-only） */
    val messages: MutableList<BoardMessage> = mutableListOf(),
    /** 投票记录 */
    val votes: MutableList<BoardVote> = mutableListOf(),
    /** 当前阶段 */
    var phase: BoardPhase = BoardPhase.IDLE,
    /** 是否达成共识 */
    var consensus: Boolean = false,
    /** 是否结束 */
    var finished: Boolean = false,
    /** 最终评级（supervisor 填写） */
    var finalRating: String? = null,
    /** 执行计划（executor 填写） */
    var executionPlan: String? = null,
)

/** 消息 */
data class BoardMessage(
    val role: String,       // agent role id: analyst, risk_officer, ...
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

/** 阶段 — 不控制流程，只标记状态 */
enum class BoardPhase {
    IDLE,               // 待机
    ANALYSIS,           // 分析阶段（analyst/intel 并行）
    DEBATE,             // 辩论阶段（多轮轮替）
    VOTE,               // 投票阶段
    RATING,             // 评级阶段（supervisor）
    EXECUTION,          // 执行阶段（executor）
    DONE,               // 结束
}

/** Agent 行为输出 */
sealed class AgentAction {
    /** 发言 */
    data class Speak(val content: String) : AgentAction()
    /** 投票 */
    data class Vote(val agree: Boolean, val reason: String = "") : AgentAction()
    /** 跳过本轮 */
    data object Pass : AgentAction()
    /** 结束会议 */
    data class Finish(val rating: String? = null) : AgentAction()
}

/** Agent 决策结果 — 包含 action + 是否需要 LLM 调用 */
data class AgentDecision(
    val action: AgentAction,
    val needsLlm: Boolean = false,
    /** 如果需要 LLM，传给 LLM 的 prompt */
    val prompt: String? = null,
)
