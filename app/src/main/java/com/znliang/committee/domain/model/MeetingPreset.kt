package com.znliang.committee.domain.model

import com.znliang.committee.R

/**
 * 会议预设模板
 *
 * @param id              唯一标识
 * @param name            预设名称
 * @param description     预设描述
 * @param iconName        图标名称（Material Icons）
 * @param committeeLabel  委员会名称（如"投委会"、"评审会"），用于 UI 显示
 * @param roles           角色列表（默认角色）
 * @param mandates        会议规则/指令（key-value 对）
 * @param ratingScale     评级量表
 */
data class MeetingPreset(
    val id: String,
    val name: String,
    val description: String,
    val iconName: String,
    val committeeLabel: String,
    val roles: List<PresetRole>,
    val mandates: Map<String, String>,
    val ratingScale: List<String>,
) {
    /** Runtime-only flag — excluded from equals/hashCode to avoid false inequality */
    @Transient var isActive: Boolean = false
    /** 通过 roleId 查找预设角色 */
    fun findRole(roleId: String): PresetRole? = roles.find { it.id == roleId }

    // ── Runtime mandate helpers ──────────────────────────────
    // mandates map keys used by runtime:
    //   max_rounds        → max discussion rounds (default "20")
    //   activation_k      → how many agents speak per round (default "2")
    //   summary_interval  → rounds between summaries (default "2")
    //   evolve_threshold  → experience count before auto-evolution (default "3")
    //   has_supervisor     → whether a supervisor role exists (default "true")
    //   output_type        → "rating" | "decision" | "score" | "open" (default "rating")
    //   vote_type          → "binary" | "scale" | "multi_stance" (default "binary")
    //   debate_rounds      → minimum rounds before finish check (default agents.size)
    //   consensus_required → require consensus before finishing (default "false")
    //   supervisor_final_call → supervisor verdict overrides vote (default "true")
    //   summary_template   → "binary" | "multi_dimension" | "convergent" (default "binary")
    //   prompt_style       → "debate" | "review" | "collaborative" | "creative" | "pitch" (default "debate")

    fun mandateInt(key: String, default: Int): Int = mandates[key]?.toIntOrNull() ?: default
    fun mandateStr(key: String, default: String): String = mandates[key] ?: default
    fun mandateBool(key: String, default: Boolean): Boolean = mandates[key]?.toBooleanStrictOrNull() ?: default

    /** @return String resource ID for localized committee label, or 0 for custom presets */
    @androidx.annotation.StringRes
    fun committeeLabelRes(): Int = Companion.committeeLabelRes(id)

    /** @return String resource ID for localized preset name, or 0 for custom presets */
    @androidx.annotation.StringRes
    fun nameRes(): Int = Companion.nameRes(id)

    companion object {
        fun fromId(id: String): MeetingPreset? = ALL_PRESETS.find { it.id == id }

        private val COMMITTEE_LABEL_RES: Map<String, Int> = mapOf(
            "investment_committee" to R.string.preset_ic_label,
            "general_meeting" to R.string.preset_gm_label,
            "product_review" to R.string.preset_pr_label,
            "tech_review" to R.string.preset_tr_label,
            "debate" to R.string.preset_db_label,
            "paper_review" to R.string.preset_paper_label,
            "startup_pitch" to R.string.preset_sp_label,
            "legal_review" to R.string.preset_lr_label,
            "incident_postmortem" to R.string.preset_ip_label,
            "brainstorm" to R.string.preset_bs_label,
        )
        private val NAME_RES: Map<String, Int> = mapOf(
            "investment_committee" to R.string.preset_ic_name,
            "general_meeting" to R.string.preset_gm_name,
            "product_review" to R.string.preset_pr_name,
            "tech_review" to R.string.preset_tr_name,
            "debate" to R.string.preset_db_name,
            "paper_review" to R.string.preset_paper_name,
            "startup_pitch" to R.string.preset_sp_name,
            "legal_review" to R.string.preset_lr_name,
            "incident_postmortem" to R.string.preset_ip_name,
            "brainstorm" to R.string.preset_bs_name,
        )

        @androidx.annotation.StringRes
        fun committeeLabelRes(id: String): Int = COMMITTEE_LABEL_RES[id] ?: 0

        @androidx.annotation.StringRes
        fun nameRes(id: String): Int = NAME_RES[id] ?: 0
    }
}

// ── Default Presets ──────────────────────────────────────────

/** Investment Committee preset — corresponds to the 6 AgentRole enum roles */
val INVESTMENT_COMMITTEE_PRESET = MeetingPreset(
    id = "investment_committee",
    name = "Investment Committee",
    description = "Investment Decision Committee — Bull/Bear debate + Risk Assessment + 6-tier Rating System",
    iconName = "account_balance",
    committeeLabel = "Investment Committee",
    roles = listOf(
        PresetRole(
            id = "analyst",
            displayName = "Analyst",
            stance = "Bull",
            responsibility = "Bull Case + Valuation Framework + Prior Forecast Review",
            systemPromptKey = "role_analyst",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "risk_officer",
            displayName = "Risk Officer",
            stance = "Bear",
            responsibility = "Bear Case + Risk Calendar + Challenge",
            systemPromptKey = "role_risk_officer",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "strategy_validator",
            displayName = "Strategist",
            stance = "Neutral / Framework",
            responsibility = "Top-down Strategy Framework + Entry Assessment + Cross-meeting Consistency",
            systemPromptKey = "role_strategist",
            colorHex = "#2196F3",
        ),
        PresetRole(
            id = "executor",
            displayName = "Executor",
            stance = "Execution",
            responsibility = "Execution Plan + Rating + Execution Tracking",
            systemPromptKey = "role_executor",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "intel",
            displayName = "Intel Agent",
            stance = "Facts",
            responsibility = "Base Intelligence + Incremental Updates",
            systemPromptKey = "role_intel",
            colorHex = "#9C27B0",
            canUseTools = true,
        ),
        PresetRole(
            id = "supervisor",
            displayName = "Supervisor",
            stance = "Adjudication",
            responsibility = "Arbitration + Minutes + Execution Discipline Tracking",
            systemPromptKey = "role_supervisor",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "debate_rounds" to "3",
        "max_rounds" to "15",
        "consensus_required" to "false",
        "supervisor_final_call" to "true",
        "has_supervisor" to "true",
        "output_type" to "rating",
        "summary_template" to "binary",
        "prompt_style" to "debate",
        "phase1_label" to "Debate",
        "phase2_label" to "Risk Assessment",
    ),
    ratingScale = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell"),
)

/** General Meeting preset — 3 generic roles */
val GENERAL_MEETING_PRESET = MeetingPreset(
    id = "general_meeting",
    name = "General Meeting",
    description = "General Review Meeting — Coordinator / Researcher / Reviewer",
    iconName = "groups",
    committeeLabel = "Review Committee",
    roles = listOf(
        PresetRole(
            id = "coordinator",
            displayName = "Coordinator",
            stance = "Neutral / Facilitation",
            responsibility = "Process Facilitation + Agenda Management + Summary",
            systemPromptKey = "role_coordinator",
            colorHex = "#1976D2",
            isSupervisor = true,
        ),
        PresetRole(
            id = "researcher",
            displayName = "Researcher",
            stance = "Investigation",
            responsibility = "Information Collection + Deep Analysis + Recommendations",
            systemPromptKey = "role_researcher",
            colorHex = "#388E3C",
            canUseTools = true,
        ),
        PresetRole(
            id = "reviewer",
            displayName = "Reviewer",
            stance = "Review",
            responsibility = "Plan Review + Risk Discovery + Improvement Suggestions",
            systemPromptKey = "role_reviewer",
            colorHex = "#E64A19",
        ),
    ),
    mandates = mapOf(
        "debate_rounds" to "3",
        "max_rounds" to "10",
        "consensus_required" to "true",
        "supervisor_final_call" to "false",
        "output_type" to "decision",
        "summary_template" to "convergent",
        "prompt_style" to "collaborative",
        "phase1_label" to "Discussion",
        "phase2_label" to "Review & Assessment",
    ),
    ratingScale = listOf("Pass", "Conditional Pass", "Revise & Re-review", "Fail"),
)

/** Product Review preset — 产品评审会 */
val PRODUCT_REVIEW_PRESET = MeetingPreset(
    id = "product_review",
    name = "Product Review",
    description = "Product Review Meeting — Cross-functional team evaluates product readiness",
    iconName = "inventory",
    committeeLabel = "Product Review Board",
    roles = listOf(
        PresetRole(
            id = "product_manager",
            displayName = "Product Manager",
            stance = "Advocate",
            responsibility = "Product Vision + Feature Justification + Roadmap Alignment",
            systemPromptKey = "role_product_manager",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "tech_lead",
            displayName = "Tech Lead",
            stance = "Feasibility",
            responsibility = "Technical Feasibility + Architecture Impact + Effort Estimation",
            systemPromptKey = "role_tech_lead",
            colorHex = "#2196F3",
        ),
        PresetRole(
            id = "designer",
            displayName = "Designer",
            stance = "UX",
            responsibility = "User Experience + Design Consistency + Accessibility",
            systemPromptKey = "role_designer",
            colorHex = "#E91E63",
        ),
        PresetRole(
            id = "qa_engineer",
            displayName = "QA Engineer",
            stance = "Quality",
            responsibility = "Test Coverage + Quality Standards + Risk Identification",
            systemPromptKey = "role_qa_engineer",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "user_advocate",
            displayName = "User Advocate",
            stance = "User Perspective",
            responsibility = "User Needs + Market Fit + Feedback Integration",
            systemPromptKey = "role_user_advocate",
            colorHex = "#9C27B0",
        ),
        PresetRole(
            id = "coordinator",
            displayName = "Coordinator",
            stance = "Neutral / Facilitation",
            responsibility = "Process Facilitation + Decision Tracking + Summary",
            systemPromptKey = "role_coordinator",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "activation_k" to "3",
        "max_rounds" to "15",
        "output_type" to "decision",
        "summary_template" to "multi_dimension",
        "has_supervisor" to "true",
        "supervisor_final_call" to "false",
        "consensus_required" to "false",
        "vote_type" to "binary",
        "prompt_style" to "review",
    ),
    ratingScale = listOf("Ship", "Ship with fixes", "Needs revision", "Reject"),
)

/** Tech Review preset — 技术方案评审 */
val TECH_REVIEW_PRESET = MeetingPreset(
    id = "tech_review",
    name = "Tech Review",
    description = "Technical Design Review — Architecture, Security, and Performance evaluation",
    iconName = "engineering",
    committeeLabel = "Tech Review Board",
    roles = listOf(
        PresetRole(
            id = "architect",
            displayName = "Architect",
            stance = "Design",
            responsibility = "Architecture Evaluation + Design Patterns + Scalability",
            systemPromptKey = "role_architect",
            colorHex = "#1976D2",
        ),
        PresetRole(
            id = "security_expert",
            displayName = "Security Expert",
            stance = "Security",
            responsibility = "Security Assessment + Threat Modeling + Compliance",
            systemPromptKey = "role_security_expert",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "perf_expert",
            displayName = "Performance Expert",
            stance = "Performance",
            responsibility = "Performance Analysis + Bottleneck Identification + Optimization",
            systemPromptKey = "role_perf_expert",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "stakeholder",
            displayName = "Business Stakeholder",
            stance = "Requirements",
            responsibility = "Business Requirements + Priority Alignment + ROI Assessment",
            systemPromptKey = "role_stakeholder",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "coordinator",
            displayName = "Coordinator",
            stance = "Neutral / Facilitation",
            responsibility = "Process Facilitation + Decision Tracking + Summary",
            systemPromptKey = "role_coordinator",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "activation_k" to "3",
        "max_rounds" to "12",
        "output_type" to "decision",
        "summary_template" to "multi_dimension",
        "has_supervisor" to "true",
        "supervisor_final_call" to "false",
        "consensus_required" to "false",
        "vote_type" to "binary",
        "prompt_style" to "review",
    ),
    ratingScale = listOf("Approve", "Approve with conditions", "Major revision needed", "Reject"),
)

/** Debate preset — 辩论赛 */
val DEBATE_PRESET = MeetingPreset(
    id = "debate",
    name = "Debate",
    description = "Structured Debate — Proponent vs Opponent with Judge adjudication",
    iconName = "gavel",
    committeeLabel = "Debate Panel",
    roles = listOf(
        PresetRole(
            id = "proponent",
            displayName = "Proponent",
            stance = "For",
            responsibility = "Argument Construction + Evidence Presentation + Rebuttal",
            systemPromptKey = "role_proponent",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "opponent",
            displayName = "Opponent",
            stance = "Against",
            responsibility = "Counter-argument + Evidence Presentation + Rebuttal",
            systemPromptKey = "role_opponent",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "judge",
            displayName = "Judge",
            stance = "Adjudication",
            responsibility = "Fair Evaluation + Scoring + Final Ruling",
            systemPromptKey = "role_judge",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "activation_k" to "1",
        "max_rounds" to "10",
        "output_type" to "rating",
        "has_supervisor" to "true",
        "vote_type" to "multi_stance",
        "summary_template" to "binary",
        "debate_rounds" to "6",
        "prompt_style" to "debate",
        "strict_alternation" to "true",
    ),
    ratingScale = listOf("Proponent wins", "Opponent wins", "Draw"),
)

/** Paper Review preset — 论文审稿 */
val PAPER_REVIEW_PRESET = MeetingPreset(
    id = "paper_review",
    name = "Paper Review",
    description = "Academic Paper Review — Multi-reviewer evaluation with Area Chair oversight",
    iconName = "article",
    committeeLabel = "Review Committee",
    roles = listOf(
        PresetRole(
            id = "reviewer_1",
            displayName = "Reviewer 1",
            stance = "Methodology",
            responsibility = "Methodology Rigor + Experimental Design + Reproducibility",
            systemPromptKey = "role_reviewer_1",
            colorHex = "#1976D2",
        ),
        PresetRole(
            id = "reviewer_2",
            displayName = "Reviewer 2",
            stance = "Novelty & Impact",
            responsibility = "Novelty Assessment + Impact Evaluation + Literature Coverage",
            systemPromptKey = "role_reviewer_2",
            colorHex = "#388E3C",
        ),
        PresetRole(
            id = "area_chair",
            displayName = "Area Chair",
            stance = "Adjudication",
            responsibility = "Meta-review + Consensus Building + Final Recommendation",
            systemPromptKey = "role_area_chair",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "activation_k" to "3",
        "max_rounds" to "10",
        "debate_rounds" to "4",
        "output_type" to "score",
        "vote_type" to "scale",
        "summary_template" to "multi_dimension",
        "prompt_style" to "review",
    ),
    ratingScale = listOf("Strong Accept", "Accept", "Weak Accept", "Borderline", "Reject"),
)

// ── New Presets (Phase 6) ────────────────────────────────────

/** Startup Pitch preset — 创业路演 */
val STARTUP_PITCH_PRESET = MeetingPreset(
    id = "startup_pitch",
    name = "Startup Pitch",
    description = "Startup Pitch Review — Founder presents, investors evaluate viability and market fit",
    iconName = "rocket_launch",
    committeeLabel = "Investment Panel",
    roles = listOf(
        PresetRole(
            id = "founder",
            displayName = "Founder",
            stance = "Advocate",
            responsibility = "Vision + Product Demo + Market Opportunity + Traction",
            systemPromptKey = "role_founder",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "lead_investor",
            displayName = "Lead Investor",
            stance = "Scrutiny",
            responsibility = "Due Diligence + Valuation + Deal Terms + Risk Assessment",
            systemPromptKey = "role_lead_investor",
            colorHex = "#F44336",
            canUseTools = true,
        ),
        PresetRole(
            id = "market_analyst",
            displayName = "Market Analyst",
            stance = "Data",
            responsibility = "Market Size + Competitive Landscape + Growth Projections",
            systemPromptKey = "role_market_analyst",
            colorHex = "#2196F3",
        ),
        PresetRole(
            id = "mentor",
            displayName = "Mentor",
            stance = "Guidance",
            responsibility = "Strategic Advice + Founder Coaching + Network Leverage",
            systemPromptKey = "role_mentor",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "deal_lead",
            displayName = "Deal Lead",
            stance = "Adjudication",
            responsibility = "Final Investment Decision + Term Sheet + Portfolio Fit",
            systemPromptKey = "role_deal_lead",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "vote_type" to "multi_stance",
        "output_type" to "decision",
        "summary_template" to "multi_dimension",
        "supervisor_final_call" to "true",
        "max_rounds" to "12",
        "activation_k" to "2",
        "prompt_style" to "pitch",
    ),
    ratingScale = listOf("Invest", "Conditional Interest", "Pass"),
)

/** Legal Review preset — 合规审查 */
val LEGAL_REVIEW_PRESET = MeetingPreset(
    id = "legal_review",
    name = "Legal Review",
    description = "Legal & Compliance Review — Cross-functional compliance assessment",
    iconName = "balance",
    committeeLabel = "Compliance Committee",
    roles = listOf(
        PresetRole(
            id = "legal_counsel",
            displayName = "Legal Counsel",
            stance = "Compliance",
            responsibility = "Legal Framework + Regulatory Requirements + Contract Review",
            systemPromptKey = "role_legal_counsel",
            colorHex = "#1976D2",
        ),
        PresetRole(
            id = "risk_manager",
            displayName = "Risk Manager",
            stance = "Risk",
            responsibility = "Risk Assessment + Mitigation Strategies + Impact Analysis",
            systemPromptKey = "role_risk_manager",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "biz_rep",
            displayName = "Business Rep",
            stance = "Feasibility",
            responsibility = "Business Impact + Implementation Cost + Timeline",
            systemPromptKey = "role_biz_rep",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "compliance_officer",
            displayName = "Compliance Officer",
            stance = "Adjudication",
            responsibility = "Final Compliance Ruling + Remediation Plan + Monitoring",
            systemPromptKey = "role_compliance_officer",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "consensus_required" to "true",
        "vote_type" to "multi_stance",
        "output_type" to "decision",
        "summary_template" to "multi_dimension",
        "max_rounds" to "10",
        "debate_rounds" to "4",
        "prompt_style" to "review",
    ),
    ratingScale = listOf("Compliant", "Conditionally Compliant", "Non-Compliant", "Blocked"),
)

/** Incident Postmortem preset — 事故复盘 */
val INCIDENT_POSTMORTEM_PRESET = MeetingPreset(
    id = "incident_postmortem",
    name = "Incident Postmortem",
    description = "Incident Postmortem — Blameless root cause analysis and action items",
    iconName = "bug_report",
    committeeLabel = "Postmortem Committee",
    roles = listOf(
        PresetRole(
            id = "incident_commander",
            displayName = "Incident Commander",
            stance = "Facts",
            responsibility = "Timeline Reconstruction + Incident Scope + Communication Log",
            systemPromptKey = "role_incident_commander",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "engineer",
            displayName = "Engineer",
            stance = "Technical",
            responsibility = "Technical Root Cause + Code/Config Analysis + Fix Verification",
            systemPromptKey = "role_engineer",
            colorHex = "#2196F3",
        ),
        PresetRole(
            id = "sre",
            displayName = "SRE",
            stance = "Systems",
            responsibility = "System Metrics + Monitoring Gaps + Infrastructure Analysis",
            systemPromptKey = "role_sre",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "pm",
            displayName = "Product Manager",
            stance = "Impact",
            responsibility = "User Impact + Business Impact + Customer Communication",
            systemPromptKey = "role_pm_postmortem",
            colorHex = "#9C27B0",
        ),
        PresetRole(
            id = "facilitator",
            displayName = "Facilitator",
            stance = "Adjudication",
            responsibility = "Blameless Facilitation + Action Item Tracking + Follow-up",
            systemPromptKey = "role_facilitator",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
    ),
    mandates = mapOf(
        "consensus_required" to "true",
        "vote_type" to "multi_stance",
        "output_type" to "open",
        "summary_template" to "convergent",
        "max_rounds" to "12",
        "activation_k" to "3",
        "prompt_style" to "collaborative",
    ),
    ratingScale = listOf("Root Cause Identified", "Partial Understanding", "Needs More Investigation"),
)

/** Brainstorm preset — 头脑风暴 */
val BRAINSTORM_PRESET = MeetingPreset(
    id = "brainstorm",
    name = "Brainstorm",
    description = "Creative Brainstorm — Divergent thinking + Convergent selection",
    iconName = "lightbulb",
    committeeLabel = "Brainstorm Session",
    roles = listOf(
        PresetRole(
            id = "facilitator_bs",
            displayName = "Facilitator",
            stance = "Adjudication",
            responsibility = "Session Flow + Idea Clustering + Energy Management",
            systemPromptKey = "role_facilitator_bs",
            colorHex = "#607D8B",
            isSupervisor = true,
        ),
        PresetRole(
            id = "visionary",
            displayName = "Visionary",
            stance = "Wild Ideas",
            responsibility = "Bold Ideas + Future Scenarios + Unconventional Thinking",
            systemPromptKey = "role_visionary",
            colorHex = "#9C27B0",
        ),
        PresetRole(
            id = "pragmatist",
            displayName = "Pragmatist",
            stance = "Feasibility",
            responsibility = "Reality Check + Resource Constraints + Implementation Path",
            systemPromptKey = "role_pragmatist",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "user_voice",
            displayName = "User Voice",
            stance = "Need",
            responsibility = "User Pain Points + Unmet Needs + Desirability Check",
            systemPromptKey = "role_user_voice",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "synthesizer",
            displayName = "Synthesizer",
            stance = "Integration",
            responsibility = "Idea Combination + Pattern Recognition + Concept Refinement",
            systemPromptKey = "role_synthesizer",
            colorHex = "#2196F3",
        ),
    ),
    mandates = mapOf(
        "supervisor_final_call" to "false",
        "vote_type" to "multi_stance",
        "output_type" to "open",
        "summary_template" to "convergent",
        "max_rounds" to "15",
        "activation_k" to "3",
        "prompt_style" to "creative",
    ),
    ratingScale = listOf("Breakthrough Idea", "Promising Direction", "Needs Iteration", "Pivot"),
)

/** 所有内置 preset 列表 */
val ALL_PRESETS: List<MeetingPreset> = listOf(
    INVESTMENT_COMMITTEE_PRESET,
    GENERAL_MEETING_PRESET,
    PRODUCT_REVIEW_PRESET,
    TECH_REVIEW_PRESET,
    DEBATE_PRESET,
    PAPER_REVIEW_PRESET,
    STARTUP_PITCH_PRESET,
    LEGAL_REVIEW_PRESET,
    INCIDENT_POSTMORTEM_PRESET,
    BRAINSTORM_PRESET,
)
