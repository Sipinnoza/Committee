package com.znliang.committee.domain.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
)

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

    companion object {
        fun fromId(id: String): MeetingPreset? = ALL_PRESETS.find { it.id == id }
    }
}

// ── Default Presets ──────────────────────────────────────────

/** 投委会 preset — 与现有 AgentRole enum 的 6 个角色完全对应 */
val INVESTMENT_COMMITTEE_PRESET = MeetingPreset(
    id = "investment_committee",
    name = "投委会",
    description = "投资决策委员会 — 多空辩论 + 风险评估 + 6级评级体系",
    iconName = "account_balance",
    committeeLabel = "投委会",
    roles = listOf(
        PresetRole(
            id = "analyst",
            displayName = "分析师",
            stance = "看多（Bull）",
            responsibility = "Bull Case + 估值框架 + 前次预测回顾",
            systemPromptKey = "role_analyst",
            colorHex = "#4CAF50",
        ),
        PresetRole(
            id = "risk_officer",
            displayName = "风险官",
            stance = "看空（Bear）",
            responsibility = "Bear Case + 风险日历 + 质疑",
            systemPromptKey = "role_risk_officer",
            colorHex = "#F44336",
        ),
        PresetRole(
            id = "strategy_validator",
            displayName = "策略师",
            stance = "中立/框架",
            responsibility = "Top-down 策略框架 + 入场评估 + 跨会议一致性",
            systemPromptKey = "role_strategist",
            colorHex = "#2196F3",
        ),
        PresetRole(
            id = "executor",
            displayName = "执行员",
            stance = "方案",
            responsibility = "执行方案 + 评级 + 执行追踪",
            systemPromptKey = "role_executor",
            colorHex = "#FF9800",
        ),
        PresetRole(
            id = "intel",
            displayName = "情报员",
            stance = "事实",
            responsibility = "基础情报 + 增量推送",
            systemPromptKey = "role_intel",
            colorHex = "#9C27B0",
        ),
        PresetRole(
            id = "supervisor",
            displayName = "监督员",
            stance = "评判",
            responsibility = "仲裁 + 纪要 + 执行纪律追踪",
            systemPromptKey = "role_supervisor",
            colorHex = "#607D8B",
        ),
    ),
    mandates = mapOf(
        "debate_rounds" to "3",
        "consensus_required" to "false",
        "supervisor_final_call" to "true",
        "phase1_label" to "多方辩论",
        "phase2_label" to "风险评估",
    ),
    ratingScale = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell"),
)

/** 通用会议 preset — 3 个通用角色 */
val GENERAL_MEETING_PRESET = MeetingPreset(
    id = "general_meeting",
    name = "通用会议",
    description = "通用评审会议 — 协调 / 研究 / 评审三角色",
    iconName = "groups",
    committeeLabel = "评审会",
    roles = listOf(
        PresetRole(
            id = "coordinator",
            displayName = "协调员",
            stance = "中立/引导",
            responsibility = "流程引导 + 议程管理 + 总结归纳",
            systemPromptKey = "role_coordinator",
            colorHex = "#1976D2",
        ),
        PresetRole(
            id = "researcher",
            displayName = "研究员",
            stance = "探究",
            responsibility = "信息收集 + 深度分析 + 方案建议",
            systemPromptKey = "role_researcher",
            colorHex = "#388E3C",
        ),
        PresetRole(
            id = "reviewer",
            displayName = "评审员",
            stance = "审视",
            responsibility = "方案审核 + 风险发现 + 改进建议",
            systemPromptKey = "role_reviewer",
            colorHex = "#E64A19",
        ),
    ),
    mandates = mapOf(
        "debate_rounds" to "2",
        "consensus_required" to "true",
        "supervisor_final_call" to "false",
        "phase1_label" to "方案讨论",
        "phase2_label" to "审核评估",
    ),
    ratingScale = listOf("通过", "有条件通过", "修改后重审", "不通过"),
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
    fun appTitle(): String = "${committeeLabel()}助手"
    fun activeRoles(): List<PresetRole> = getActivePreset().roles
    fun activeRatingScale(): List<String> = getActivePreset().ratingScale
    fun activeMandates(): Map<String, String> = getActivePreset().mandates
}
