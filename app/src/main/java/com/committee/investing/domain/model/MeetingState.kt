package com.committee.investing.domain.model

/**
 * 投委会会议状态机 — 与规格文档 §2.1 完全对应
 */
enum class MeetingState(val displayName: String, val description: String) {
    IDLE("待机", "等待发起新会议"),
    VALIDATING("入场评估", "策略师检查前序决议执行与标的适格性"),
    REJECTED("已拒绝", "入场评估未通过，等待重新发起"),
    PREPPING("并行准备", "四路并行：分析师、风险官、情报员、策略师"),
    PHASE1_DEBATE("多方辩论", "Bull vs Bear 三方轮替辩论"),
    PHASE1_ADJUDICATING("监督员仲裁", "轮次用尽，监督员强制裁决"),
    PHASE2_ASSESSMENT("风险评估", "执行员提方案，风险官挑战"),
    FINAL_RATING("发布评级", "执行员发布最终评级与执行方案"),
    APPROVED("已批准", "等待用户确认执行"),
    COMPLETED("会议完成", "监督员写纪要，准备回到待机");

    companion object {
        /**
         * ⚠️ 已弃用：状态转移规则现在从 flow DSL JSON 加载（assets/flows/default_flow.json）
         * 保留此列表仅作为文档参考，StateEngine 不再使用它
         */
        @Deprecated("状态转移规则已迁移到 flow DSL，见 assets/flows/default_flow.json")
        val TRANSITIONS: List<Transition> = listOf(
            Transition(IDLE,                "meeting_requested",    VALIDATING),
            Transition(VALIDATING,          "validation_passed",    PREPPING),
            Transition(VALIDATING,          "validation_rejected",  REJECTED),
            Transition(REJECTED,            "meeting_requested",    VALIDATING),
            Transition(PREPPING,            "all_ready",            PHASE1_DEBATE),
            Transition(PHASE1_DEBATE,       "consensus_reached",    PHASE2_ASSESSMENT),
            Transition(PHASE1_DEBATE,       "max_rounds_reached",   PHASE1_ADJUDICATING),
            Transition(PHASE1_ADJUDICATING, "adjudication_complete",PHASE2_ASSESSMENT),
            Transition(PHASE2_ASSESSMENT,   "consensus_reached",    FINAL_RATING),
            Transition(PHASE2_ASSESSMENT,   "max_rounds_reached",   FINAL_RATING),
            Transition(PHASE2_ASSESSMENT,   "plan_finalized",       FINAL_RATING),
            Transition(FINAL_RATING,        "rating_approved",      APPROVED),
            Transition(APPROVED,            "execution_confirmed",  COMPLETED),
            Transition(COMPLETED,           "minutes_published",    IDLE),
            Transition(COMPLETED,           "meeting_requested",    VALIDATING),
            // 任意状态可取消
            Transition(null,               "meeting_cancelled",    IDLE),
        )
    }
}

data class Transition(
    val from: MeetingState?,   // null = wildcard "*"
    val on: String,
    val to: MeetingState,
)
