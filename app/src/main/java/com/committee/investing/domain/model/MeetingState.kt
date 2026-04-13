package com.committee.investing.domain.model

/**
 * 投委会会议状态（UI 映射用）
 */
enum class MeetingState(val displayName: String, val description: String) {
    IDLE("待机", "等待发起新会议"),
    VALIDATING("入场评估", "策略师检查前序决议执行与标的适格性"),
    REJECTED("已拒绝", "入场评估未通过"),
    PREPPING("并行准备", "四路并行准备中"),
    PHASE1_DEBATE("多方辩论", "Bull vs Bear 辩论"),
    PHASE1_ADJUDICATING("监督员仲裁", "轮次用尽，监督员裁决"),
    PHASE2_ASSESSMENT("风险评估", "执行员提方案，风险官挑战"),
    FINAL_RATING("发布评级", "发布最终评级与执行方案"),
    APPROVED("已批准", "等待用户确认执行"),
    COMPLETED("会议完成", "会议结束");
}
