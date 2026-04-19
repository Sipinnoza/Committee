package com.znliang.committee.domain.model

import com.znliang.committee.R

/**
 * Meeting role definitions, corresponding to spec §1.2
 *
 * displayName/stance/responsibility are English defaults.
 * UI code should use displayNameRes()/stanceRes()/responsibilityRes() for localized strings.
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
        displayName = "Analyst",
        stance = "Bull",
        responsibility = "Bull Case + Valuation Framework + Prior Forecast Review",
        systemPromptKey = "role_analyst",
    ),
    RISK_OFFICER(
        id = "risk_officer",
        displayName = "Risk Officer",
        stance = "Bear",
        responsibility = "Bear Case + Risk Calendar + Challenge",
        systemPromptKey = "role_risk_officer",
    ),
    STRATEGIST(
        id = "strategy_validator",
        displayName = "Strategist",
        stance = "Neutral / Framework",
        responsibility = "Top-down Strategy Framework + Entry Assessment + Cross-meeting Consistency",
        systemPromptKey = "role_strategist",
    ),
    EXECUTOR(
        id = "executor",
        displayName = "Executor",
        stance = "Execution",
        responsibility = "Execution Plan + Rating + Execution Tracking",
        systemPromptKey = "role_executor",
    ),
    INTEL(
        id = "intel",
        displayName = "Intel Agent",
        stance = "Facts",
        responsibility = "Base Intelligence + Incremental Updates",
        systemPromptKey = "role_intel",
    ),
    SUPERVISOR(
        id = "supervisor",
        displayName = "Supervisor",
        stance = "Adjudication",
        responsibility = "Arbitration + Minutes + Execution Discipline Tracking",
        systemPromptKey = "role_supervisor",
    );

    /** @return String resource ID for the localized display name */
    fun displayNameRes(): Int = when (this) {
        ANALYST     -> R.string.role_analyst_display
        RISK_OFFICER -> R.string.role_risk_display
        STRATEGIST  -> R.string.role_strategist_display
        EXECUTOR    -> R.string.role_executor_display
        INTEL       -> R.string.role_intel_display
        SUPERVISOR  -> R.string.role_supervisor_display
    }

    /** @return String resource ID for the localized stance */
    fun stanceRes(): Int = when (this) {
        ANALYST     -> R.string.role_analyst_stance
        RISK_OFFICER -> R.string.role_risk_stance
        STRATEGIST  -> R.string.role_strategist_stance
        EXECUTOR    -> R.string.role_executor_stance
        INTEL       -> R.string.role_intel_stance
        SUPERVISOR  -> R.string.role_supervisor_stance
    }

    /** @return String resource ID for the localized responsibility */
    fun responsibilityRes(): Int = when (this) {
        ANALYST     -> R.string.role_analyst_resp
        RISK_OFFICER -> R.string.role_risk_resp
        STRATEGIST  -> R.string.role_strategist_resp
        EXECUTOR    -> R.string.role_executor_resp
        INTEL       -> R.string.role_intel_resp
        SUPERVISOR  -> R.string.role_supervisor_resp
    }

    companion object {
        fun fromId(id: String): AgentRole? = entries.find { it.id == id }
    }
}
