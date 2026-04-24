package com.znliang.committee.domain.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private val PREF_ACTIVE_PRESET = stringPreferencesKey("active_preset_id")
private val PREF_CUSTOM_ROLES_PREFIX = "custom_roles_"
private val PREF_CUSTOM_PRESETS = stringPreferencesKey("custom_presets_v1")
private val PREF_SKILLS_SEEDED_PREFIX = "skills_seeded_"

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
    // Scope lives as long as the process — acceptable for @Singleton with Eagerly-shared StateFlow.
    // If strict lifecycle control is needed, inject @ApplicationScope from DI instead.
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

    // ── 推荐技能 seed flag ────────────────────────────────────────

    /** 指定 preset 的推荐技能是否已 seed */
    suspend fun isSkillsSeeded(presetId: String): Boolean {
        val key = booleanPreferencesKey("${PREF_SKILLS_SEEDED_PREFIX}${presetId}")
        val prefs = prefsFlow.value.takeIf { it.asMap().isNotEmpty() }
            ?: dataStore.data.first()
        return prefs[key] == true
    }

    /** 标记指定 preset 的推荐技能已 seed */
    suspend fun markSkillsSeeded(presetId: String) {
        val key = booleanPreferencesKey("${PREF_SKILLS_SEEDED_PREFIX}${presetId}")
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(key, true) }
        }
    }
}
