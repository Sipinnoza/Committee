package com.committee.investing.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.committee.investing.engine.LlmConfig
import com.committee.investing.engine.LlmProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val PREF_API_KEY = stringPreferencesKey("api_key")
private val PREF_LLM_PROVIDER = stringPreferencesKey("llm_provider")
private val PREF_LLM_MODEL = stringPreferencesKey("llm_model")
private val PREF_LLM_BASE_URL = stringPreferencesKey("llm_base_url")

// Per-agent config keys
private fun agentProviderKey(roleId: String) = stringPreferencesKey("agent_${roleId}_provider")
private fun agentModelKey(roleId: String) = stringPreferencesKey("agent_${roleId}_model")
private fun agentApiKeyKey(roleId: String) = stringPreferencesKey("agent_${roleId}_apiKey")
private fun agentBaseUrlKey(roleId: String) = stringPreferencesKey("agent_${roleId}_baseUrl")

@Singleton
class DataStoreApiKeyProvider @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    fun getKey(): String = runBlocking {
        dataStore.data.first()[PREF_API_KEY] ?: ""
    }

    fun hasKey(): Boolean = getKey().isNotBlank()

    suspend fun saveKey(key: String) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(PREF_API_KEY, key) }
        }
    }

    fun getConfig(): LlmConfig = runBlocking {
        val prefs = dataStore.data.first()
        val providerId = prefs[PREF_LLM_PROVIDER] ?: LlmProvider.DEEPSEEK.id
        val provider = LlmProvider.fromId(providerId)
        val savedModel = prefs[PREF_LLM_MODEL]
        val savedBaseUrl = prefs[PREF_LLM_BASE_URL]
        val model = savedModel?.takeIf { it in provider.models } ?: provider.defaultModel
        // 只接受真正的自定义代理URL，忽略其他provider的残留defaultBaseUrl
        val baseUrl = savedBaseUrl?.takeIf { it.isNotBlank() && it !in LlmProvider.entries.map { p -> p.defaultBaseUrl } }
            ?: provider.defaultBaseUrl
        LlmConfig(
            provider = provider,
            apiKey = prefs[PREF_API_KEY] ?: "",
            model = model,
            baseUrl = baseUrl,
        )
    }

    suspend fun saveConfig(config: LlmConfig) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(PREF_API_KEY, config.apiKey)
                set(PREF_LLM_PROVIDER, config.provider.id)
                set(PREF_LLM_MODEL, config.model)
                set(PREF_LLM_BASE_URL, config.baseUrl)
            }
        }
    }

    // -- Per-Agent Config --

    fun getAgentConfig(roleId: String): LlmConfig = runBlocking {
        val prefs = dataStore.data.first()
        val global = getConfig()
        val agentProviderId = prefs[agentProviderKey(roleId)]
        val agentModel = prefs[agentModelKey(roleId)]
        val agentApiKey = prefs[agentApiKeyKey(roleId)]
        val agentBaseUrl = prefs[agentBaseUrlKey(roleId)]
        if (agentProviderId == null && agentModel == null && agentApiKey == null) {
            return@runBlocking global
        }
        val provider = agentProviderId?.let { LlmProvider.fromId(it) } ?: global.provider
        val model = agentModel?.takeIf { it in provider.models } ?: provider.defaultModel
        // 只接受真正的自定义代理URL，忽略其他provider的残留defaultBaseUrl
        val baseUrl = agentBaseUrl?.takeIf { it.isNotBlank() && it !in LlmProvider.entries.map { p -> p.defaultBaseUrl } }
            ?: provider.defaultBaseUrl
        val apiKey = agentApiKey?.takeIf { it.isNotBlank() } ?: global.apiKey
        LlmConfig(provider = provider, apiKey = apiKey, model = model, baseUrl = baseUrl)
    }

    suspend fun saveAgentConfig(roleId: String, config: LlmConfig) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(agentProviderKey(roleId), config.provider.id)
                set(agentModelKey(roleId), config.model)
                set(agentApiKeyKey(roleId), config.apiKey)
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
}
