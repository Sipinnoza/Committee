package com.znliang.committee.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.R
import com.znliang.committee.data.db.AgentChatDao
import com.znliang.committee.data.db.AgentChatMessageEntity
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.engine.LlmConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AgentChatMessage(
    val id: Long = 0,
    val role: String,       // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false,
)

data class AgentChatUiState(
    val agentRoleId: String = "",
    val agentDisplayName: String = "",
    val agentSystemPromptKey: String = "",
    val systemPrompt: String = "",
    val messages: List<AgentChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val agentConfig: LlmConfig = LlmConfig(),
    val isUsingCustomConfig: Boolean = false,
    val isEditingPrompt: Boolean = false,
    val promptSource: String = "",
    val promptSuggestion: String = "",
)

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentPool: AgentPool,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val chatDao: AgentChatDao,
    private val runtime: com.znliang.committee.engine.runtime.AgentRuntime,
    private val presetConfig: MeetingPresetConfig,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState())
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private var currentRoleId: String = ""
    private var chatCollectorJob: Job? = null
    private var suggestionCollectorJob: Job? = null

    fun setAgent(roleId: String, displayName: String = "", systemPromptKey: String = "") {
        currentRoleId = roleId
        val presetRole = presetConfig.getActivePreset().findRole(roleId)
        val systemPrompt = agentPool.getSystemPromptTextByRoleId(roleId) ?: ""
        val agentConfig = apiKeyProvider.getAgentConfigCached(roleId)
        val globalConfig = apiKeyProvider.getConfigCached()
        val isCustom = agentConfig != globalConfig

        val promptKey = presetRole?.systemPromptKey ?: systemPromptKey
        val localFile = File(appContext.filesDir, "prompts/${promptKey}.txt")
        val promptSource = if (localFile.exists()) "local_file" else "assets"

        _uiState.value = _uiState.value.copy(
            agentRoleId = roleId,
            agentDisplayName = displayName,
            agentSystemPromptKey = promptKey,
            systemPrompt = systemPrompt,
            agentConfig = agentConfig,
            isUsingCustomConfig = isCustom,
            promptSource = promptSource,
            isEditingPrompt = false,
        )

        // Cancel previous collectors to prevent coroutine leaks
        chatCollectorJob?.cancel()
        chatCollectorJob = viewModelScope.launch {
            chatDao.observeMessages(roleId).collect { entities ->
                _uiState.value = _uiState.value.copy(
                    messages = entities.map {
                        AgentChatMessage(id = it.id, role = it.role, content = it.content)
                    }
                )
            }
        }

        suggestionCollectorJob?.cancel()
        suggestionCollectorJob = viewModelScope.launch {
            runtime.promptSuggestions.collect { suggestions ->
                val mySuggestion = suggestions[roleId] ?: ""
                _uiState.value = _uiState.value.copy(promptSuggestion = mySuggestion)
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (!apiKeyProvider.hasKey()) {
            _uiState.value = _uiState.value.copy(error = appContext.getString(R.string.error_configure_api_key))
            return
        }

        val now = System.currentTimeMillis()

        // Persist user message
        viewModelScope.launch {
            chatDao.insert(AgentChatMessageEntity(
                agentRole = currentRoleId,
                role = "user",
                content = text,
                ts = now,
            ))
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            // Insert a placeholder for the streaming response
            val placeholderId = chatDao.insert(AgentChatMessageEntity(
                agentRole = currentRoleId,
                role = "assistant",
                content = "",
                ts = now + 1,
            ))

            try {
                val roleId = currentRoleId
                val ctx = MicContext(
                    traceId = "chat_$now",
                    causedBy = "",
                    round = 1,
                    phase = MeetingState.IDLE,
                    subject = text,
                    agentRoleId = roleId,
                    task = "direct_chat",
                )

                var fullContent = ""
                agentPool.callAgentStreamingByRoleId(roleId, ctx).collect { result ->
                    when (result) {
                        is com.znliang.committee.engine.StreamResult.Token -> {
                            fullContent += result.text
                            // Update placeholder in memory state
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages.map {
                                    if (it.id == placeholderId) it.copy(content = fullContent, isStreaming = true) else it
                                }
                            )
                        }
                        is com.znliang.committee.engine.StreamResult.Error -> {
                            fullContent += "\n⚠️ ${result.message}"
                        }
                        is com.znliang.committee.engine.StreamResult.Done -> { /* no-op */ }
                    }
                }

                // Persist final content
                chatDao.insert(AgentChatMessageEntity(
                    id = placeholderId,
                    agentRole = currentRoleId,
                    role = "assistant",
                    content = fullContent,
                    ts = now + 1,
                ))

                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.map {
                        if (it.id == placeholderId) it.copy(isStreaming = false) else it
                    },
                    isLoading = false,
                )
            } catch (e: Exception) {
                val errMsg = "${e.javaClass.simpleName}: ${e.message ?: appContext.getString(R.string.error_unknown)}"
                chatDao.insert(AgentChatMessageEntity(
                    id = placeholderId,
                    agentRole = currentRoleId,
                    role = "assistant",
                    content = appContext.getString(R.string.error_format, errMsg),
                    ts = now + 1,
                ))
                _uiState.value = _uiState.value.copy(isLoading = false, error = errMsg)
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch { chatDao.clearChat(currentRoleId) }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    // ── Prompt editing ──────────────────────────────────────────────────

    fun setEditingPrompt(editing: Boolean) {
        _uiState.value = _uiState.value.copy(isEditingPrompt = editing)
    }

    /** Save custom prompt to filesDir/prompts/{key}.txt */
    fun savePrompt(newPrompt: String) {
        val promptKey = _uiState.value.agentSystemPromptKey
        val dir = File(appContext.filesDir, "prompts")
        dir.mkdirs()
        val file = File(dir, "${promptKey}.txt")
        file.writeText(newPrompt)

        _uiState.value = _uiState.value.copy(
            systemPrompt = newPrompt,
            isEditingPrompt = false,
            promptSource = "local_file",
        )
    }

    /** Reset prompt to asset default */
    fun resetPrompt() {
        val promptKey = _uiState.value.agentSystemPromptKey
        val file = File(appContext.filesDir, "prompts/${promptKey}.txt")
        if (file.exists()) file.delete()

        val defaultPrompt = agentPool.getSystemPromptTextByRoleId(currentRoleId) ?: ""

        _uiState.value = _uiState.value.copy(
            systemPrompt = defaultPrompt,
            isEditingPrompt = false,
            promptSource = "assets",
        )
    }

    /**
     * 从 LLM 生成的优化建议中提取 SUGGESTION 内容，
     * 追加到当前 prompt 末尾作为「自我优化备注」，保存到本地文件。
     */
    fun applyPromptSuggestion() {
        val suggestion = _uiState.value.promptSuggestion
        if (suggestion.isBlank()) return

        val role = _uiState.value.agentRoleId
        val currentPrompt = _uiState.value.systemPrompt

        // 提取 SUGGESTION 行
        val suggestionLine = suggestion.lines()
            .firstOrNull { it.trim().startsWith("SUGGESTION:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?: return

        if (suggestionLine.contains("无需修改") || suggestionLine.isBlank()) {
            dismissSuggestion()
            return
        }

        // 追加优化备注到当前 prompt
        val optimizedPrompt = buildString {
            append(currentPrompt.trimEnd())
            appendLine()
            appendLine()
            appendLine("## 自我优化记录（${java.time.LocalDate.now()}）")
            append(suggestionLine)
        }

        savePrompt(optimizedPrompt)
        dismissSuggestion()
    }

    /** 忽略建议 */
    fun dismissSuggestion() {
        runtime.clearSuggestion(currentRoleId)
        _uiState.value = _uiState.value.copy(promptSuggestion = "")
    }

    fun saveAgentConfig(config: LlmConfig) {
        viewModelScope.launch {
            apiKeyProvider.saveAgentConfig(currentRoleId, config)
            _uiState.value = _uiState.value.copy(agentConfig = config, isUsingCustomConfig = true)
        }
    }

    fun resetAgentConfig() {
        viewModelScope.launch {
            val globalConfig = apiKeyProvider.getConfig()
            apiKeyProvider.clearAgentConfig(currentRoleId)
            _uiState.value = _uiState.value.copy(agentConfig = globalConfig, isUsingCustomConfig = false)
        }
    }
}
