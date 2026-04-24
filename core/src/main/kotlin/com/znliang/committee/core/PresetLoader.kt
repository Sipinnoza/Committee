package com.znliang.committee.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Reader

/**
 * Loads preset definitions and skill catalogs from JSON.
 *
 * This lives in the :core module (pure JVM) and accepts [Reader] inputs
 * so the Android layer can feed it from AssetManager, file, or test fixtures.
 *
 * Usage from Android:
 * ```
 * val presets = PresetLoader.loadPresets(
 *     context.assets.open("presets/all_presets.json").reader()
 * )
 * val skills = PresetLoader.loadSkillCatalog(
 *     context.assets.open("presets/skill_catalog.json").reader()
 * )
 * ```
 */
object PresetLoader {

    private val gson = Gson()

    // ── JSON mirror types (no Android dependencies) ──────────────────

    data class PresetJson(
        val id: String,
        val name: String,
        val description: String,
        val iconName: String,
        val committeeLabel: String,
        val roles: List<RoleJson>,
        val mandates: Map<String, String>,
        val ratingScale: List<String>,
    )

    data class RoleJson(
        val id: String,
        val displayName: String,
        val stance: String,
        val responsibility: String,
        val systemPromptKey: String,
        val colorHex: String,
        val canUseTools: Boolean = false,
        val isSupervisor: Boolean = false,
    )

    data class SkillJson(
        val name: String,
        val description: String,
        val parameters: String,
        val executionType: String,
        val executionConfig: String,
    )

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Parse `all_presets.json` into a list of [PresetJson].
     * The reader is closed after parsing.
     */
    fun loadPresets(reader: Reader): List<PresetJson> {
        return reader.use {
            val type = object : TypeToken<List<PresetJson>>() {}.type
            gson.fromJson(it, type)
        }
    }

    /**
     * Parse `skill_catalog.json` into a map of presetId -> skill list.
     * The reader is closed after parsing.
     */
    fun loadSkillCatalog(reader: Reader): Map<String, List<SkillJson>> {
        return reader.use {
            val type = object : TypeToken<Map<String, List<SkillJson>>>() {}.type
            gson.fromJson(it, type)
        }
    }

    /**
     * Convenience: load presets from a JSON string.
     */
    fun loadPresetsFromString(json: String): List<PresetJson> =
        loadPresets(json.reader())

    /**
     * Convenience: load skill catalog from a JSON string.
     */
    fun loadSkillCatalogFromString(json: String): Map<String, List<SkillJson>> =
        loadSkillCatalog(json.reader())
}
