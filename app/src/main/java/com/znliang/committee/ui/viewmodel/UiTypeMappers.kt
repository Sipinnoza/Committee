package com.znliang.committee.ui.viewmodel

import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.engine.runtime.BoardVote
import com.znliang.committee.engine.runtime.ContributionScore
import com.znliang.committee.engine.runtime.MaterialRef
import com.znliang.committee.ui.model.ContributionInfo
import com.znliang.committee.ui.model.MaterialItem
import com.znliang.committee.ui.model.UiPhase
import com.znliang.committee.ui.model.VoteInfo

/**
 * Mapping extensions that convert engine.runtime types to UI-layer types.
 *
 * These live in the ViewModel package because ViewModels are the boundary
 * between the engine layer and the UI layer. Screen/component code should
 * never need to import engine.runtime.* directly.
 */

// ── BoardPhase -> UiPhase ────────────────────────────────────

fun BoardPhase.toUiPhase(): UiPhase = when (this) {
    BoardPhase.IDLE -> UiPhase.IDLE
    BoardPhase.ANALYSIS -> UiPhase.ANALYSIS
    BoardPhase.DEBATE -> UiPhase.DEBATE
    BoardPhase.VOTE -> UiPhase.VOTE
    BoardPhase.RATING -> UiPhase.RATING
    BoardPhase.EXECUTION -> UiPhase.EXECUTION
    BoardPhase.DONE -> UiPhase.DONE
}

// ── BoardVote -> VoteInfo ────────────────────────────────────

fun BoardVote.toVoteInfo(): VoteInfo = VoteInfo(
    role = role,
    agree = agree,
    reason = reason,
    round = round,
    numericScore = numericScore,
    stanceLabel = stanceLabel,
)

fun Map<String, BoardVote>.toVoteInfoMap(): Map<String, VoteInfo> =
    mapValues { (_, v) -> v.toVoteInfo() }

// ── ContributionScore -> ContributionInfo ────────────────────

fun ContributionScore.toContributionInfo(): ContributionInfo = ContributionInfo(
    roleId = roleId,
    informationGain = informationGain,
    logicQuality = logicQuality,
    interactionQuality = interactionQuality,
    brief = brief,
    overall = overall,
)

fun Map<String, ContributionScore>.toContributionInfoMap(): Map<String, ContributionInfo> =
    mapValues { (_, v) -> v.toContributionInfo() }

// ── MaterialRef -> MaterialItem ──────────────────────────────

fun MaterialRef.toMaterialItem(): MaterialItem = MaterialItem(
    id = id,
    fileName = fileName,
    mimeType = mimeType,
    description = description,
    base64 = base64,
)

fun List<MaterialRef>.toMaterialItems(): List<MaterialItem> =
    map { it.toMaterialItem() }

// ── MaterialItem -> MaterialRef (reverse, for UI -> engine) ──

fun MaterialItem.toMaterialRef(): MaterialRef = MaterialRef(
    id = id,
    fileName = fileName,
    mimeType = mimeType,
    description = description,
    base64 = base64,
)

fun List<MaterialItem>.toMaterialRefs(): List<MaterialRef> =
    map { it.toMaterialRef() }
