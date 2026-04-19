package com.znliang.committee.domain.model

import com.znliang.committee.R

/**
 * Meeting states (UI mapping)
 *
 * displayName/description are English defaults.
 * UI code should use displayNameRes()/descriptionRes() to get localized strings.
 */
enum class MeetingState(val displayName: String, val description: String) {
    IDLE("Idle", "Waiting for new meeting"),
    VALIDATING("Entry Assessment", "Strategist checks prior resolution execution & target eligibility"),
    REJECTED("Rejected", "Entry assessment failed"),
    PREPPING("Parallel Prep", "Four parallel preparations in progress"),
    PHASE1_DEBATE("Debate", "Bull vs Bear debate"),
    PHASE1_ADJUDICATING("Adjudicating", "Rounds exhausted, supervisor adjudicates"),
    PHASE2_ASSESSMENT("Risk Assessment", "Executor proposes, risk officer challenges"),
    FINAL_RATING("Final Rating", "Publish final rating & execution plan"),
    APPROVED("Approved", "Awaiting user execution confirmation"),
    COMPLETED("Completed", "Meeting concluded");

    /** @return String resource ID for the localized display name */
    fun displayNameRes(): Int = when (this) {
        IDLE                -> R.string.home_state_idle
        VALIDATING          -> R.string.home_state_validating
        REJECTED            -> R.string.home_state_rejected
        PREPPING            -> R.string.home_state_prepping
        PHASE1_DEBATE       -> R.string.home_state_phase1_debate
        PHASE1_ADJUDICATING -> R.string.home_state_phase1_adjudicating
        PHASE2_ASSESSMENT   -> R.string.home_state_phase2_assessment
        FINAL_RATING        -> R.string.home_state_final_rating
        APPROVED            -> R.string.home_state_approved
        COMPLETED           -> R.string.home_state_completed
    }

    /** @return String resource ID for the localized description */
    fun descriptionRes(): Int = when (this) {
        IDLE                -> R.string.home_state_desc_idle
        VALIDATING          -> R.string.home_state_desc_validating
        REJECTED            -> R.string.home_state_desc_rejected
        PREPPING            -> R.string.home_state_desc_prepping
        PHASE1_DEBATE       -> R.string.home_state_desc_debate
        PHASE1_ADJUDICATING -> R.string.home_state_desc_adjudicating
        PHASE2_ASSESSMENT   -> R.string.home_state_desc_assessment
        FINAL_RATING        -> R.string.home_state_desc_rating
        APPROVED            -> R.string.home_state_desc_approved
        COMPLETED           -> R.string.home_state_desc_completed
    }
}
