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
        )
        private val NAME_RES: Map<String, Int> = mapOf(
            "investment_committee" to R.string.preset_ic_name,
            "general_meeting" to R.string.preset_gm_name,
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

/** 所有内置 preset 列表 */
val ALL_PRESETS: List<MeetingPreset> = listOf(
    INVESTMENT_COMMITTEE_PRESET,
    GENERAL_MEETING_PRESET,
)

// ── Preset Config Manager ────────────────────────────────────

private val PREF_ACTIVE_PRESET = stringPreferencesKey("active_preset_id")
private val PREF_CUSTOM_ROLES_PREFIX = "custom_roles_"

private val gson = Gson()
private val roleListType = object : TypeToken<List<PresetRole>>() {}.type

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

    /** 获取指定 preset（合并自定义角色） */
    fun getPreset(presetId: String): MeetingPreset {
        val base = MeetingPreset.fromId(presetId) ?: INVESTMENT_COMMITTEE_PRESET
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
        val base = MeetingPreset.fromId(presetId) ?: INVESTMENT_COMMITTEE_PRESET
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

    /** 监听当前 preset 变化（Flow，合并自定义角色） */
    fun activePresetFlow() = dataStore.data.map { prefs ->
        val presetId = prefs[PREF_ACTIVE_PRESET] ?: INVESTMENT_COMMITTEE_PRESET.id
        val base = MeetingPreset.fromId(presetId) ?: INVESTMENT_COMMITTEE_PRESET
        val custom = readCustomRoles(prefs, presetId)
        if (custom != null) base.copy(roles = custom) else base
    }

    // ── 便捷访问方法 ─────────────────────────────────────────

    fun committeeLabel(): String = getActivePreset().committeeLabel
    fun appTitle(): String = "${committeeLabel()} Assistant"
    fun activeRoles(): List<PresetRole> = getActivePreset().roles
    fun activeRatingScale(): List<String> = getActivePreset().ratingScale
    fun activeMandates(): Map<String, String> = getActivePreset().mandates
}
