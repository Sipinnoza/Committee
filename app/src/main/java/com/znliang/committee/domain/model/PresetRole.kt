package com.znliang.committee.domain.model

import com.znliang.committee.R

/**
 * 会议预设角色模板
 */
data class PresetRole(
    val id: String,                    // 角色唯一标识
    val displayName: String,           // 角色显示名称
    val stance: String,                // 角色立场（如Bull/Bear/Neutral）
    val responsibility: String,        // 角色职责描述
    val systemPromptKey: String,       // 系统Prompt模板的资源key
    val colorHex: String,              // 角色主题色（十六进制）
    val canUseTools: Boolean = false,  // 是否可使用工具（如搜索）
    val isSupervisor: Boolean = false, // 是否为主持人/裁判角色
) {
    companion object {
        /** Map role id → string resource IDs for i18n */
        private val DISPLAY_NAME_RES: Map<String, Int> by lazy { mapOf(
            "analyst" to R.string.role_analyst_display,
            "risk_officer" to R.string.role_risk_display,
            "strategy_validator" to R.string.role_strategist_display,
            "executor" to R.string.role_executor_display,
            "intel" to R.string.role_intel_display,
            "supervisor" to R.string.role_supervisor_display,
            "coordinator" to R.string.preset_gm_coordinator_name,
            "researcher" to R.string.preset_gm_researcher_name,
            "reviewer" to R.string.preset_gm_reviewer_name,
            // product_review roles
            "product_manager" to R.string.role_pm_display,
            "tech_lead" to R.string.role_tech_lead_display,
            "designer" to R.string.role_designer_display,
            "qa_engineer" to R.string.role_qa_display,
            "user_advocate" to R.string.role_user_advocate_display,
            // tech_review roles
            "architect" to R.string.role_architect_display,
            "security_expert" to R.string.role_security_display,
            "perf_expert" to R.string.role_perf_display,
            "stakeholder" to R.string.role_stakeholder_display,
            // debate roles
            "proponent" to R.string.role_proponent_display,
            "opponent" to R.string.role_opponent_display,
            "judge" to R.string.role_judge_display,
            // paper_review roles
            "reviewer_1" to R.string.role_reviewer1_display,
            "reviewer_2" to R.string.role_reviewer2_display,
            "area_chair" to R.string.role_area_chair_display,
            // startup_pitch roles
            "founder" to R.string.role_founder_display,
            "lead_investor" to R.string.role_lead_investor_display,
            "market_analyst" to R.string.role_market_analyst_display,
            "mentor" to R.string.role_mentor_display,
            "deal_lead" to R.string.role_deal_lead_display,
            // legal_review roles
            "legal_counsel" to R.string.role_legal_counsel_display,
            "risk_manager" to R.string.role_risk_manager_display,
            "biz_rep" to R.string.role_biz_rep_display,
            "compliance_officer" to R.string.role_compliance_officer_display,
            // incident_postmortem roles
            "incident_commander" to R.string.role_incident_commander_display,
            "engineer" to R.string.role_engineer_display,
            "sre" to R.string.role_sre_display,
            "pm" to R.string.role_pm_postmortem_display,
            "facilitator" to R.string.role_facilitator_display,
            // brainstorm roles
            "facilitator_bs" to R.string.role_facilitator_bs_display,
            "visionary" to R.string.role_visionary_display,
            "pragmatist" to R.string.role_pragmatist_display,
            "user_voice" to R.string.role_user_voice_display,
            "synthesizer" to R.string.role_synthesizer_display,
            // hiring roles
            "hiring_manager" to R.string.role_hiring_manager_display,
            "tech_interviewer" to R.string.role_tech_interviewer_display,
            "culture_assessor" to R.string.role_culture_assessor_display,
            "bar_raiser" to R.string.role_bar_raiser_display,
            "hr_coordinator" to R.string.role_hr_coordinator_display,
            // budget roles
            "budget_owner" to R.string.role_budget_owner_display,
            "finance_controller" to R.string.role_finance_controller_display,
            "strategy_advisor" to R.string.role_strategy_advisor_display,
            "cfo" to R.string.role_cfo_display,
            // marketing roles
            "creative_director" to R.string.role_creative_director_display,
            "data_analyst" to R.string.role_data_analyst_display,
            "brand_manager" to R.string.role_brand_manager_display,
            "growth_hacker" to R.string.role_growth_hacker_display,
            "cmo" to R.string.role_cmo_display,
        ) }
        private val STANCE_RES: Map<String, Int> by lazy { mapOf(
            "analyst" to R.string.role_analyst_stance,
            "risk_officer" to R.string.role_risk_stance,
            "strategy_validator" to R.string.role_strategist_stance,
            "executor" to R.string.role_executor_stance,
            "intel" to R.string.role_intel_stance,
            "supervisor" to R.string.role_supervisor_stance,
            "coordinator" to R.string.preset_gm_coordinator_stance,
            "researcher" to R.string.preset_gm_researcher_stance,
            "reviewer" to R.string.preset_gm_reviewer_stance,
            // product_review roles
            "product_manager" to R.string.role_pm_stance,
            "tech_lead" to R.string.role_tech_lead_stance,
            "designer" to R.string.role_designer_stance,
            "qa_engineer" to R.string.role_qa_stance,
            "user_advocate" to R.string.role_user_advocate_stance,
            // tech_review roles
            "architect" to R.string.role_architect_stance,
            "security_expert" to R.string.role_security_stance,
            "perf_expert" to R.string.role_perf_stance,
            "stakeholder" to R.string.role_stakeholder_stance,
            // debate roles
            "proponent" to R.string.role_proponent_stance,
            "opponent" to R.string.role_opponent_stance,
            "judge" to R.string.role_judge_stance,
            // paper_review roles
            "reviewer_1" to R.string.role_reviewer1_stance,
            "reviewer_2" to R.string.role_reviewer2_stance,
            "area_chair" to R.string.role_area_chair_stance,
            // startup_pitch roles
            "founder" to R.string.role_founder_stance,
            "lead_investor" to R.string.role_lead_investor_stance,
            "market_analyst" to R.string.role_market_analyst_stance,
            "mentor" to R.string.role_mentor_stance,
            "deal_lead" to R.string.role_deal_lead_stance,
            // legal_review roles
            "legal_counsel" to R.string.role_legal_counsel_stance,
            "risk_manager" to R.string.role_risk_manager_stance,
            "biz_rep" to R.string.role_biz_rep_stance,
            "compliance_officer" to R.string.role_compliance_officer_stance,
            // incident_postmortem roles
            "incident_commander" to R.string.role_incident_commander_stance,
            "engineer" to R.string.role_engineer_stance,
            "sre" to R.string.role_sre_stance,
            "pm" to R.string.role_pm_postmortem_stance,
            "facilitator" to R.string.role_facilitator_stance,
            // brainstorm roles
            "facilitator_bs" to R.string.role_facilitator_bs_stance,
            "visionary" to R.string.role_visionary_stance,
            "pragmatist" to R.string.role_pragmatist_stance,
            "user_voice" to R.string.role_user_voice_stance,
            "synthesizer" to R.string.role_synthesizer_stance,
            // hiring roles
            "hiring_manager" to R.string.role_hiring_manager_stance,
            "tech_interviewer" to R.string.role_tech_interviewer_stance,
            "culture_assessor" to R.string.role_culture_assessor_stance,
            "bar_raiser" to R.string.role_bar_raiser_stance,
            "hr_coordinator" to R.string.role_hr_coordinator_stance,
            // budget roles
            "budget_owner" to R.string.role_budget_owner_stance,
            "finance_controller" to R.string.role_finance_controller_stance,
            "strategy_advisor" to R.string.role_strategy_advisor_stance,
            "cfo" to R.string.role_cfo_stance,
            // marketing roles
            "creative_director" to R.string.role_creative_director_stance,
            "data_analyst" to R.string.role_data_analyst_stance,
            "brand_manager" to R.string.role_brand_manager_stance,
            "growth_hacker" to R.string.role_growth_hacker_stance,
            "cmo" to R.string.role_cmo_stance,
        ) }
        private val RESPONSIBILITY_RES: Map<String, Int> by lazy { mapOf(
            "analyst" to R.string.role_analyst_resp,
            "risk_officer" to R.string.role_risk_resp,
            "strategy_validator" to R.string.role_strategist_resp,
            "executor" to R.string.role_executor_resp,
            "intel" to R.string.role_intel_resp,
            "supervisor" to R.string.role_supervisor_resp,
            "coordinator" to R.string.preset_gm_coordinator_resp,
            "researcher" to R.string.preset_gm_researcher_resp,
            "reviewer" to R.string.preset_gm_reviewer_resp,
            // product_review roles
            "product_manager" to R.string.role_pm_resp,
            "tech_lead" to R.string.role_tech_lead_resp,
            "designer" to R.string.role_designer_resp,
            "qa_engineer" to R.string.role_qa_resp,
            "user_advocate" to R.string.role_user_advocate_resp,
            // tech_review roles
            "architect" to R.string.role_architect_resp,
            "security_expert" to R.string.role_security_resp,
            "perf_expert" to R.string.role_perf_resp,
            "stakeholder" to R.string.role_stakeholder_resp,
            // debate roles
            "proponent" to R.string.role_proponent_resp,
            "opponent" to R.string.role_opponent_resp,
            "judge" to R.string.role_judge_resp,
            // paper_review roles
            "reviewer_1" to R.string.role_reviewer1_resp,
            "reviewer_2" to R.string.role_reviewer2_resp,
            "area_chair" to R.string.role_area_chair_resp,
            // startup_pitch roles
            "founder" to R.string.role_founder_resp,
            "lead_investor" to R.string.role_lead_investor_resp,
            "market_analyst" to R.string.role_market_analyst_resp,
            "mentor" to R.string.role_mentor_resp,
            "deal_lead" to R.string.role_deal_lead_resp,
            // legal_review roles
            "legal_counsel" to R.string.role_legal_counsel_resp,
            "risk_manager" to R.string.role_risk_manager_resp,
            "biz_rep" to R.string.role_biz_rep_resp,
            "compliance_officer" to R.string.role_compliance_officer_resp,
            // incident_postmortem roles
            "incident_commander" to R.string.role_incident_commander_resp,
            "engineer" to R.string.role_engineer_resp,
            "sre" to R.string.role_sre_resp,
            "pm" to R.string.role_pm_postmortem_resp,
            "facilitator" to R.string.role_facilitator_resp,
            // brainstorm roles
            "facilitator_bs" to R.string.role_facilitator_bs_resp,
            "visionary" to R.string.role_visionary_resp,
            "pragmatist" to R.string.role_pragmatist_resp,
            "user_voice" to R.string.role_user_voice_resp,
            "synthesizer" to R.string.role_synthesizer_resp,
            // hiring roles
            "hiring_manager" to R.string.role_hiring_manager_resp,
            "tech_interviewer" to R.string.role_tech_interviewer_resp,
            "culture_assessor" to R.string.role_culture_assessor_resp,
            "bar_raiser" to R.string.role_bar_raiser_resp,
            "hr_coordinator" to R.string.role_hr_coordinator_resp,
            // budget roles
            "budget_owner" to R.string.role_budget_owner_resp,
            "finance_controller" to R.string.role_finance_controller_resp,
            "strategy_advisor" to R.string.role_strategy_advisor_resp,
            "cfo" to R.string.role_cfo_resp,
            // marketing roles
            "creative_director" to R.string.role_creative_director_resp,
            "data_analyst" to R.string.role_data_analyst_resp,
            "brand_manager" to R.string.role_brand_manager_resp,
            "growth_hacker" to R.string.role_growth_hacker_resp,
            "cmo" to R.string.role_cmo_resp,
        ) }

        /** Resolve a role id to its display name string resource, or 0 if unknown */
        @JvmStatic
        @androidx.annotation.StringRes
        fun displayNameResForId(roleId: String): Int = DISPLAY_NAME_RES[roleId] ?: 0
    }

    /** @return String resource ID for localized display name, or 0 for custom roles */
    @androidx.annotation.StringRes
    fun displayNameRes(): Int = DISPLAY_NAME_RES[id] ?: 0

    /** @return String resource ID for localized stance, or 0 for custom roles */
    @androidx.annotation.StringRes
    fun stanceRes(): Int = STANCE_RES[id] ?: 0

    /** @return String resource ID for localized responsibility, or 0 for custom roles */
    @androidx.annotation.StringRes
    fun responsibilityRes(): Int = RESPONSIBILITY_RES[id] ?: 0
}
