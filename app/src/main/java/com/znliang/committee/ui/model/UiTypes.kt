package com.znliang.committee.ui.model

/**
 * UI-layer value types that mirror engine.runtime types.
 *
 * The UI layer (screens, components) should never import engine.runtime
 * types directly. ViewModels convert engine types to these UI types
 * before exposing them via StateFlow.
 */

// ── Phase ────────────────────────────────────────────────────

/**
 * UI-facing meeting phase. Mirrors [com.znliang.committee.engine.runtime.BoardPhase].
 */
enum class UiPhase {
    IDLE, ANALYSIS, DEBATE, VOTE, RATING, EXECUTION, DONE,
}

// ── Vote ─────────────────────────────────────────────────────

/**
 * UI-friendly vote representation. Mirrors [com.znliang.committee.engine.runtime.BoardVote].
 */
data class VoteInfo(
    val role: String,
    val agree: Boolean,
    val reason: String = "",
    val round: Int = 0,
    val numericScore: Int? = null,
    val stanceLabel: String? = null,
)

// ── Contribution ─────────────────────────────────────────────

/**
 * UI-friendly contribution score. Mirrors [com.znliang.committee.engine.runtime.ContributionScore].
 */
data class ContributionInfo(
    val roleId: String,
    val informationGain: Int = 0,
    val logicQuality: Int = 0,
    val interactionQuality: Int = 0,
    val brief: String = "",
    val overall: Float = 0f,
)

// ── Material ─────────────────────────────────────────────────

/**
 * UI-friendly material/attachment reference. Mirrors [com.znliang.committee.engine.runtime.MaterialRef].
 */
data class MaterialItem(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val description: String = "",
    val base64: String = "",
)
