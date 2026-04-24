package com.znliang.committee.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.LlmProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

private val PREF_API_KEY = stringPreferencesKey("api_key")
private val PREF_LLM_PROVIDER = stringPreferencesKey("llm_provider")
private val PREF_LLM_MODEL = stringPreferencesKey("llm_model")
private val PREF_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
private val PREF_TAVILY_KEY = stringPreferencesKey("tavily_api_key")

// Per-agent config keys
private fun agentProviderKey(roleId: String) = stringPreferencesKey("agent_${roleId}_provider")
private fun agentModelKey(roleId: String) = stringPreferencesKey("agent_${roleId}_model")
private fun agentApiKeyKey(roleId: String) = stringPreferencesKey("agent_${roleId}_apiKey")
private fun agentBaseUrlKey(roleId: String) = stringPreferencesKey("agent_${roleId}_baseUrl")

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  DataStoreApiKeyProvider（v2 — 消除 runBlocking）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  修复：
 *    - getConfig() / getAgentConfig() 不再使用 runBlocking
 *    - 通过 StateFlow 缓存 preferences，读取时从缓存取
 *    - 首次访问时若缓存未就绪，使用 first() 挂起读取
 *
 *  注意：
 *    - hasKey() / getKey() 保留为 sync（从缓存读），用于 UI 快速检查
 *    - getConfig() 改为 suspend，调用方需在协程中调用
 */
@Singleton
class DataStoreApiKeyProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 缓存 preferences StateFlow，避免每次 runBlocking */
    private val prefsFlow: StateFlow<Preferences> = dataStore.data
        .stateIn(scope, SharingStarted.Eagerly, emptyPreferences())

    private fun emptyPreferences(): Preferences = androidx.datastore.preferences.core.mutablePreferencesOf()

    // ── Sync 方法（从缓存读，无 runBlocking） ─────────────────────

    fun hasKey(): Boolean {
        val raw = prefsFlow.value[PREF_API_KEY] ?: ""
        return KeystoreCipher.decrypt(raw).isNotBlank()
    }

    fun getKey(): String = KeystoreCipher.decrypt(prefsFlow.value[PREF_API_KEY] ?: "")

    /** 从缓存读取全局 config（同步，适合在 viewModelScope.launch 内用） */
    fun getConfigCached(): LlmConfig = buildConfig(prefsFlow.value)

    /** 从缓存读取 per-agent config（同步） */
    fun getAgentConfigCached(roleId: String): LlmConfig = buildAgentConfig(prefsFlow.value, roleId)

    // ── Suspend 方法 ──────────────────────────────────────────────

    suspend fun getConfig(): LlmConfig {
        val prefs = prefsFlow.value.takeIf { it.asMap().isNotEmpty() } ?: dataStore.data.first()
        return buildConfig(prefs)
    }

    suspend fun getAgentConfig(roleId: String): LlmConfig {
        val prefs = prefsFlow.value.takeIf { it.asMap().isNotEmpty() } ?: dataStore.data.first()
        return buildAgentConfig(prefs, roleId)
    }

    suspend fun saveKey(key: String) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(PREF_API_KEY, KeystoreCipher.encrypt(key)) }
        }
    }

    suspend fun saveConfig(config: LlmConfig) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(PREF_API_KEY, KeystoreCipher.encrypt(config.apiKey))
                set(PREF_LLM_PROVIDER, config.provider.id)
                set(PREF_LLM_MODEL, config.model)
                set(PREF_LLM_BASE_URL, config.baseUrl)
            }
        }
    }

    suspend fun saveAgentConfig(roleId: String, config: LlmConfig) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(agentProviderKey(roleId), config.provider.id)
                set(agentModelKey(roleId), config.model)
                set(agentApiKeyKey(roleId), KeystoreCipher.encrypt(config.apiKey))
                set(agentBaseUrlKey(roleId), config.baseUrl)
            }
        }
    }

    suspend fun clearAgentConfig(roleId: String) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                remove(agentProviderKey(roleId))
                remove(agentModelKey(roleId))
                remove(agentApiKeyKey(roleId))
                remove(agentBaseUrlKey(roleId))
            }
        }
    }


    // ── Tavily API Key ──────────────────────────────────────────

    fun getTavilyKeyCached(): String = KeystoreCipher.decrypt(prefsFlow.value[PREF_TAVILY_KEY] ?: "")

    suspend fun getTavilyKey(): String {
        return KeystoreCipher.decrypt(prefsFlow.value[PREF_TAVILY_KEY] ?: "")
    }

    suspend fun saveTavilyKey(key: String) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(PREF_TAVILY_KEY, KeystoreCipher.encrypt(key)) }
        }
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun buildConfig(prefs: Preferences): LlmConfig {
        val providerId = prefs[PREF_LLM_PROVIDER] ?: LlmProvider.DEEPSEEK.id
        val provider = LlmProvider.fromId(providerId)
        val savedModel = prefs[PREF_LLM_MODEL]
        val savedBaseUrl = prefs[PREF_LLM_BASE_URL]
        val model = savedModel?.takeIf { it in provider.models } ?: provider.defaultModel
        val baseUrl = savedBaseUrl?.takeIf { it.isNotBlank() && it !in LlmProvider.entries.map { p -> p.defaultBaseUrl } }
            ?: provider.defaultBaseUrl
        return LlmConfig(
            provider = provider,
            apiKey = KeystoreCipher.decrypt(prefs[PREF_API_KEY] ?: ""),
            model = model,
            baseUrl = baseUrl,
        )
    }

    private fun buildAgentConfig(prefs: Preferences, roleId: String): LlmConfig {
        val global = buildConfig(prefs)
        val agentProviderId = prefs[agentProviderKey(roleId)]
        val agentModel = prefs[agentModelKey(roleId)]
        val agentApiKey = prefs[agentApiKeyKey(roleId)]
        if (agentProviderId == null && agentModel == null && agentApiKey == null) {
            return global
        }
        val provider = agentProviderId?.let { LlmProvider.fromId(it) } ?: global.provider
        val model = agentModel?.takeIf { it in provider.models } ?: provider.defaultModel
        val baseUrl = prefs[agentBaseUrlKey(roleId)]
            ?.takeIf { it.isNotBlank() && it !in LlmProvider.entries.map { p -> p.defaultBaseUrl } }
            ?: provider.defaultBaseUrl
        val apiKey = KeystoreCipher.decrypt(agentApiKey?.takeIf { it.isNotBlank() } ?: "") .ifBlank { global.apiKey }
        return LlmConfig(provider = provider, apiKey = apiKey, model = model, baseUrl = baseUrl)
    }
}
