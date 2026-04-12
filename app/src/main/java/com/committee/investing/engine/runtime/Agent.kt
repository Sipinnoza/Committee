package com.committee.investing.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Agent 抽象 — 每个 Agent 拥有"是否行动"的自主决策能力
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  核心原则：
 *    1. Agent 自己决定是否行动（shouldAct）
 *    2. Agent 不知道其他 Agent 的存在（只看 Blackboard）
 *    3. 不允许外部强制指定执行顺序
 *    4. Agent.act() 是纯逻辑决策，LLM 调用由 Runtime 执行
 */

interface Agent {
    /** 角色 id（analyst, risk_officer, strategist, executor, intel, supervisor） */
    val role: String

    /** 显示名 */
    val displayName: String

    /**
     * 判断当前是否应该行动
     *
     * Agent 根据 Blackboard 上的信息自主判断：
     * - 是否有人先发言了我才能说？
     * - 是否已经投过票了？
     * - 是否已经结束？
     *
     * @return true = 我要行动，false = 我跳过
     */
    fun shouldAct(board: Blackboard): Boolean

    /**
     * 执行行动（纯逻辑，不含 LLM 调用）
     *
     * Agent 根据 Blackboard 状态决定：
     * - 发言内容（需要 LLM 时返回 needsLlm=true + prompt）
     * - 投票
     * - 跳过
     * - 结束会议
     *
     * @return AgentDecision 包含 action 和是否需要 LLM
     */
    fun act(board: Blackboard): AgentDecision
}

/**
 * Supervisor 特化接口 — 调度核心
 *
 * Supervisor 不只是"总结角色"，而是整个系统的调度器：
 * - 每轮首先执行
 * - 决定下一个该谁行动
 * - 判断是否该结束
 */
interface SupervisorCapability : Agent {
    /**
     * 选择下一个应该行动的 Agent
     *
     * @param agents 所有注册的 Agent（不含自己）
     * @param board 当前状态
     * @return 被选中的 Agent，或 null（表示本轮结束，进入下一轮）
     */
    fun decideNextAgent(agents: List<Agent>, board: Blackboard): Agent?

    /**
     * 判断会议是否应该结束
     *
     * 结束条件（由 Supervisor 自主判断）：
     * - 共识已达成
     * - 达到最大轮次
     * - 所有 Agent 都 Pass
     * - 某些关键信息已经明确
     */
    fun shouldFinish(board: Blackboard): Boolean
}
