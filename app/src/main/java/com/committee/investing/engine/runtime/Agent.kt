package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Agent — 自主决策单元
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心原则：
 *    Agent 自己决定是否发言（shouldAct → 问 LLM）
 *    Agent 自己决定说什么（act → prompt → LLM）
 *    系统不控制 Agent，只提供平台
 *
 *  shouldAct 的本质变化：
 *    ❌ 旧：return board.messages.any { it.role == "analyst" }  （规则）
 *    ✅ 新：问 LLM "你是否需要发言？" → YES/NO              （决策）
 */

/** 轻量 LLM 调用器 —— 用于 shouldAct / 投票等短回答场景 */
typealias QuickLlm = suspend (prompt: String) -> String

interface Agent {
    val role: String
    val displayName: String

    /**
     * 基本资格过滤（不需要 LLM，纯计算）
     * 只排除明确不该说话的情况（会议已结束、本轮已发言过多次等）
     */
    fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        // 安全阀：同一 agent 每轮最多发言 2 次，防止单个 agent 霸占对话
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        return spokenThisRound < 2
    }

    /**
     * 🔥 LLM 驱动决策：这个 Agent 是否想发言？
     * 调用 LLM，传入 buildShouldActPrompt()，解析 YES/NO
     */
    suspend fun shouldAct(board: Blackboard, llm: QuickLlm): Boolean {
        val prompt = buildShouldActPrompt(board)
        if (prompt.isBlank()) return false
        val response = llm(prompt)
        return parseYesNo(response)
    }

    /** 构建 shouldAct 的 prompt —— 每个 Agent 各自实现 */
    fun buildShouldActPrompt(board: Blackboard): String

    /** Agent 决策：要说什么（已有逻辑不变） */
    fun act(board: Blackboard): AgentDecision

    /** 解析 YES/NO 回答 */
    fun parseYesNo(response: String): Boolean {
        val r = response.trim().uppercase()
        return r.startsWith("YES") || r.contains("AGREE") || r.startsWith("是") || r.startsWith("Y")
    }
}

/**
 * Supervisor 扩展接口 —— 主席拥有额外的裁判权
 */
interface SupervisorCapability : Agent {

    /**
     * 🔥 LLM 驱动：会议是否应该结束？
     * 不是 round > 10 的系统兜底，而是 Supervisor 看了讨论后的判断
     */
    suspend fun shouldFinish(board: Blackboard, llm: QuickLlm): Boolean {
        val prompt = buildFinishPrompt(board)
        if (prompt.isBlank()) return false
        val response = llm(prompt)
        return parseYesNo(response)
    }

    /** 构建结束判断 prompt */
    fun buildFinishPrompt(board: Blackboard): String

    /** 构建监督评论 prompt（轮间点评） */
    fun buildSupervisionPrompt(board: Blackboard): String

    /** 构建最终评级 prompt */
    fun buildRatingPrompt(board: Blackboard): String
}
