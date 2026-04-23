package com.znliang.committee.domain.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.znliang.committee.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

// ── Data Models ──────────────────────────────────────────────

/**
 * 会议预设角色模板
 */
data class PresetRole(
    val id: String,
    val displayName: String,
    val stance: String,
    val responsibility: String,
    val systemPromptKey: String,
    val colorHex: String,
    val canUseTools: Boolean = false,
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
        ) }
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
 * @param isActive        是否为当前激活 preset（仅运行时使用，不持久化）
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
    val isActive: Boolean = false,
) {
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
        )
        private val NAME_RES: Map<String, Int> = mapOf(
            "investment_committee" to R.string.preset_ic_name,
            "general_meeting" to R.string.preset_gm_name,
            "product_review" to R.string.preset_pr_name,
            "tech_review" to R.string.preset_tr_name,
            "debate" to R.string.preset_db_name,
            "paper_review" to R.string.preset_paper_name,
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
        ),
    ),
    mandates = mapOf(
        "debate_rounds" to "3",
        "consensus_required" to "false",
        "supervisor_final_call" to "true",
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
        "debate_rounds" to "2",
        "consensus_required" to "true",
        "supervisor_final_call" to "false",
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
        ),
    ),
    mandates = mapOf(
        "activation_k" to "2",
        "max_rounds" to "15",
        "output_type" to "decision",
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
        ),
    ),
    mandates = mapOf(
        "activation_k" to "2",
        "max_rounds" to "12",
        "output_type" to "decision",
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
        ),
    ),
    mandates = mapOf(
        "activation_k" to "1",
        "max_rounds" to "10",
        "output_type" to "rating",
        "has_supervisor" to "true",
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
        ),
    ),
    mandates = mapOf(
        "activation_k" to "2",
        "max_rounds" to "10",
        "output_type" to "score",
    ),
    ratingScale = listOf("Strong Accept", "Accept", "Weak Accept", "Borderline", "Reject"),
)

/** 所有内置 preset 列表 */
val ALL_PRESETS: List<MeetingPreset> = listOf(
    INVESTMENT_COMMITTEE_PRESET,
    GENERAL_MEETING_PRESET,
    PRODUCT_REVIEW_PRESET,
    TECH_REVIEW_PRESET,
    DEBATE_PRESET,
    PAPER_REVIEW_PRESET,
)

// ── Preset Config Manager ────────────────────────────────────

private val PREF_ACTIVE_PRESET = stringPreferencesKey("active_preset_id")
private val PREF_CUSTOM_ROLES_PREFIX = "custom_roles_"
private val PREF_CUSTOM_PRESETS = stringPreferencesKey("custom_presets_v1")

private val gson = Gson()
private val roleListType = object : TypeToken<List<PresetRole>>() {}.type
private val presetListType = object : TypeToken<List<MeetingPreset>>() {}.type

/**
 * 会议预设配置管理器
 *
 * 负责管理当前激活的 preset 和自定义角色，通过 DataStore 持久化。
 * 支持角色增删改和恢复默认。
 */
@Singleton
class MeetingPresetConfig @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefsFlow: StateFlow<Preferences> = dataStore.data
        .stateIn(scope, SharingStarted.Eagerly, emptyPreferences())

    private fun emptyPreferences(): Preferences =
        androidx.datastore.preferences.core.mutablePreferencesOf()

    // ── 自定义角色 CRUD ──────────────────────────────────────

    /** 读取自定义角色列表（null 表示未自定义，使用默认） */
    private fun readCustomRoles(prefs: Preferences, presetId: String): List<PresetRole>? {
        val key = stringPreferencesKey("${PREF_CUSTOM_ROLES_PREFIX}${presetId}")
        val json = prefs[key] ?: return null
        return try { gson.fromJson<List<PresetRole>>(json, roleListType) } catch (_: Exception) { null }
    }

    /** 保存自定义角色列表 */
    suspend fun saveCustomRoles(presetId: String, roles: List<PresetRole>) {
        val key = stringPreferencesKey("${PREF_CUSTOM_ROLES_PREFIX}${presetId}")
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(key, gson.toJson(roles))
            }
        }
    }

    /** 恢复默认角色（删除自定义覆盖） */
    suspend fun resetToDefaultRoles(presetId: String) {
        val key = stringPreferencesKey("${PREF_CUSTOM_ROLES_PREFIX}${presetId}")
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { remove(key) }
        }
    }

    /** 是否有自定义角色 */
    fun hasCustomRoles(presetId: String): Boolean {
        return readCustomRoles(prefsFlow.value, presetId) != null
    }

    // ── 获取 preset（合并自定义角色） ────────────────────────

    /** 获取指定 preset（合并自定义角色，含自定义预设） */
    fun getPreset(presetId: String): MeetingPreset {
        val base = MeetingPreset.fromId(presetId)
            ?: readCustomPresets(prefsFlow.value).find { it.id == presetId }
            ?: INVESTMENT_COMMITTEE_PRESET
        val custom = readCustomRoles(prefsFlow.value, presetId)
        return if (custom != null) base.copy(roles = custom) else base
    }

    /** 获取当前激活的 preset（同步，合并自定义角色） */
    fun getActivePreset(): MeetingPreset {
        val presetId = prefsFlow.value[PREF_ACTIVE_PRESET] ?: INVESTMENT_COMMITTEE_PRESET.id
        return getPreset(presetId)
    }

    /** 获取当前激活 preset（挂起，确保 DataStore 就绪） */
    suspend fun getActivePresetSuspend(): MeetingPreset {
        val prefs = prefsFlow.value.takeIf { it.asMap().isNotEmpty() }
            ?: dataStore.data.first()
        val presetId = prefs[PREF_ACTIVE_PRESET] ?: INVESTMENT_COMMITTEE_PRESET.id
        val base = MeetingPreset.fromId(presetId)
            ?: readCustomPresets(prefs).find { it.id == presetId }
            ?: INVESTMENT_COMMITTEE_PRESET
        val custom = readCustomRoles(prefs, presetId)
        return if (custom != null) base.copy(roles = custom) else base
    }

    /** 切换激活的 preset（持久化到 DataStore） */
    suspend fun setActivePreset(presetId: String) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(PREF_ACTIVE_PRESET, presetId)
            }
        }
    }

    /** 监听当前 preset 变化（Flow，合并自定义角色，含自定义预设） */
    fun activePresetFlow() = dataStore.data.map { prefs ->
        val presetId = prefs[PREF_ACTIVE_PRESET] ?: INVESTMENT_COMMITTEE_PRESET.id
        val base = MeetingPreset.fromId(presetId)
            ?: readCustomPresets(prefs).find { it.id == presetId }
            ?: INVESTMENT_COMMITTEE_PRESET
        val custom = readCustomRoles(prefs, presetId)
        if (custom != null) base.copy(roles = custom) else base
    }

    // ── 便捷访问方法 ─────────────────────────────────────────

    fun committeeLabel(): String = getActivePreset().committeeLabel
    fun appTitle(): String = "${committeeLabel()} Assistant"
    fun activeRoles(): List<PresetRole> = getActivePreset().roles
    fun activeRatingScale(): List<String> = getActivePreset().ratingScale
    fun activeMandates(): Map<String, String> = getActivePreset().mandates

    // ── 自定义预设 CRUD ─────────────────────────────────────────

    /** 读取自定义预设列表 */
    private fun readCustomPresets(prefs: Preferences): List<MeetingPreset> {
        val json = prefs[PREF_CUSTOM_PRESETS] ?: return emptyList()
        return try { gson.fromJson<List<MeetingPreset>>(json, presetListType) } catch (_: Exception) { emptyList() }
    }

    /** 获取所有自定义预设 */
    fun getCustomPresets(): List<MeetingPreset> = readCustomPresets(prefsFlow.value)

    /** 获取所有预设（内置 + 自定义） */
    fun getAllPresets(): List<MeetingPreset> = ALL_PRESETS + getCustomPresets()

    /** 保存自定义预设 */
    suspend fun saveCustomPreset(preset: MeetingPreset) {
        dataStore.updateData { prefs ->
            val existing = readCustomPresets(prefs).toMutableList()
            val idx = existing.indexOfFirst { it.id == preset.id }
            if (idx >= 0) existing[idx] = preset else existing.add(preset)
            prefs.toMutablePreferences().apply {
                set(PREF_CUSTOM_PRESETS, gson.toJson(existing))
            }
        }
    }

    /** 删除自定义预设 */
    suspend fun deleteCustomPreset(presetId: String) {
        dataStore.updateData { prefs ->
            val existing = readCustomPresets(prefs).filter { it.id != presetId }
            prefs.toMutablePreferences().apply {
                set(PREF_CUSTOM_PRESETS, gson.toJson(existing))
                // 如果删除的是当前活动预设，切回默认
                if (prefs[PREF_ACTIVE_PRESET] == presetId) {
                    set(PREF_ACTIVE_PRESET, ALL_PRESETS.first().id)
                }
            }
        }
    }

    /** 查找预设（内置 + 自定义） */
    fun findPreset(presetId: String): MeetingPreset? =
        MeetingPreset.fromId(presetId) ?: readCustomPresets(prefsFlow.value).find { it.id == presetId }
}
