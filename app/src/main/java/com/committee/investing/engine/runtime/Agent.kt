package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Agent — 自主决策单元（优化版）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心优化：shouldAct + act + vote 三合一
 *  一次 LLM 调用返回 UnifiedResponse
 *  不再分 shouldAct(LLM) + act(LLM) + vote(LLM) = 3次
 *
 *  Attention 机制：每个 Agent 声明自己关注的标签
 *  只拿相关消息构建 prompt → context ↓ token ↓
 */

/** 轻量 LLM 调用器 */
typealias QuickLlm = suspend (prompt: String) -> String

interface Agent {
    val role: String
    val displayName: String

    /** 🔥 这个 Agent 关注的消息标签（Attention） */
    val attentionTags: List<String>
        get() = emptyList()

    /** 这个 Agent 是否能投票 */
    val canVote: Boolean
        get() = true

    /** 基本资格过滤（纯计算，不调 LLM） */
    fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        return spokenThisRound < 2
    }

    /**
     * 🔥 统一入口：一次 LLM 调用返回 SPEAK + CONTENT + VOTE
     * 这是唯一的 LLM 交互点
     */
    suspend fun respond(board: Blackboard, llm: QuickLlm): UnifiedResponse {
        val prompt = buildUnifiedPrompt(board)
        if (prompt.isBlank()) return UnifiedResponse(false, "", null, emptyList())
        val raw = llm(prompt)
        return UnifiedResponse.parse(raw, canVote)
    }

    /** 构建统一的 prompt（替代原来的 buildShouldActPrompt + act + vote） */
    fun buildUnifiedPrompt(board: Blackboard): String

    /** 旧接口保留兼容（不需要了，但 CommitteeLooper 等旧代码可能引用） */
    fun act(board: Blackboard): AgentDecision {
        return AgentDecision(action = AgentAction.Speak(""), needsLlm = true, prompt = "")
    }

    /** 🔥 Attention：获取与当前 Agent 相关的消息 */
    fun relevantMessages(board: Blackboard): List<BoardMessage> {
        return if (attentionTags.isEmpty()) board.messages.takeLast(8)
        else board.messagesByTags(*attentionTags.toTypedArray()).takeLast(6)
    }
}

/**
 * Supervisor 扩展接口
 */
interface SupervisorCapability : Agent {
    override val canVote: Boolean
        get() = false

    /** 构建结束判断 prompt */
    fun buildFinishPrompt(board: Blackboard): String

    /** 构建监督评论 prompt */
    fun buildSupervisionPrompt(board: Blackboard): String

    /** 构建最终评级 prompt */
    fun buildRatingPrompt(board: Blackboard): String
}
