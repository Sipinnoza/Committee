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
    val role: String,               // 投票角色ID
    val agree: Boolean,             // 是否赞成
    val reason: String = "",        // 投票理由
    val round: Int = 0,             // 投票轮次
    val numericScore: Int? = null,  // SCALE模式数字评分(1-10)
    val stanceLabel: String? = null, // MULTI_STANCE模式立场标签
)

// ── Contribution ─────────────────────────────────────────────

/**
 * UI-friendly contribution score. Mirrors [com.znliang.committee.engine.runtime.ContributionScore].
 */
data class ContributionInfo(
    val roleId: String,             // 角色ID
    val informationGain: Int = 0,   // 信息增量评分(1-5)
    val logicQuality: Int = 0,      // 论证逻辑评分(1-5)
    val interactionQuality: Int = 0, // 互动质量评分(1-5)
    val brief: String = "",         // 一句话点评
    val overall: Float = 0f,        // 综合得分
)

// ── Material ─────────────────────────────────────────────────

/**
 * UI-friendly material/attachment reference. Mirrors [com.znliang.committee.engine.runtime.MaterialRef].
 */
data class MaterialItem(
    val id: Long,                   // 材料唯一ID
    val fileName: String,           // 文件名
    val mimeType: String,           // MIME类型
    val description: String = "",   // 文件描述
    val base64: String = "",        // base64编码数据
)
