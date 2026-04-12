package com.committee.investing.domain.model

/**
 * 投委会六大角色，与规格文档 §1.2 完全对应
 */
enum class AgentRole(
    val id: String,
    val displayName: String,
    val stance: String,
    val responsibility: String,
    val systemPromptKey: String,
) {
    ANALYST(
        id = "analyst",
        displayName = "分析师",
        stance = "看多（Bull）",
        responsibility = "Bull Case + 估值框架 + 前次预测回顾",
        systemPromptKey = "role_analyst",
    ),
    RISK_OFFICER(
        id = "risk_officer",
        displayName = "风险官",
        stance = "看空（Bear）",
        responsibility = "Bear Case + 风险日历 + 质疑",
        systemPromptKey = "role_risk_officer",
    ),
    STRATEGIST(
        id = "strategy_validator",
        displayName = "策略师",
        stance = "中立/框架",
        responsibility = "Top-down 策略框架 + 入场评估 + 跨会议一致性",
        systemPromptKey = "role_strategist",
    ),
    EXECUTOR(
        id = "executor",
        displayName = "执行员",
        stance = "方案",
        responsibility = "执行方案 + 评级 + 执行追踪",
        systemPromptKey = "role_executor",
    ),
    INTEL(
        id = "intel",
        displayName = "情报员",
        stance = "事实",
        responsibility = "基础情报 + 增量推送",
        systemPromptKey = "role_intel",
    ),
    SUPERVISOR(
        id = "supervisor",
        displayName = "监督员",
        stance = "评判",
        responsibility = "仲裁 + 纪要 + 执行纪律追踪",
        systemPromptKey = "role_supervisor",
    );

    companion object {
        fun fromId(id: String): AgentRole? = entries.find { it.id == id }
    }
}
