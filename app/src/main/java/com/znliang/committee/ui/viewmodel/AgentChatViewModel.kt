package com.znliang.committee.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.db.AgentChatDao
import com.znliang.committee.data.db.AgentChatMessageEntity
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.domain.model.AgentRole
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.engine.LlmConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    val agentRole: AgentRole = AgentRole.ANALYST,
    val systemPrompt: String = "",
    val messages: List<AgentChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val agentConfig: LlmConfig = LlmConfig(),
    val isUsingCustomConfig: Boolean = false,
    val isEditingPrompt: Boolean = false,
    val promptSource: String = "",   // "内置" / "assets" / "本地文件"
    val promptSuggestion: String = "",  // 自优化建议内容
)

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentPool: AgentPool,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val chatDao: AgentChatDao,
    private val runtime: com.znliang.committee.engine.runtime.AgentRuntime,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentChatUiState())
    val uiState: StateFlow<AgentChatUiState> = _uiState.asStateFlow()

    private var currentRoleId: String = ""

    fun setAgent(role: AgentRole) {
        currentRoleId = role.id
        val systemPrompt = agentPool.getSystemPromptText(role)
        val agentConfig = apiKeyProvider.getAgentConfigCached(role.id)
        val globalConfig = apiKeyProvider.getConfigCached()
        val isCustom = agentConfig != globalConfig

        // Detect prompt source
        val localFile = File(appContext.filesDir, "prompts/${role.systemPromptKey}.txt")
        val promptSource = if (localFile.exists()) "本地文件" else "assets"

        _uiState.value = _uiState.value.copy(
            agentRole = role,
            systemPrompt = systemPrompt,
            agentConfig = agentConfig,
            isUsingCustomConfig = isCustom,
            promptSource = promptSource,
            isEditingPrompt = false,
        )

        // Load persisted chat from Room
        viewModelScope.launch {
            chatDao.observeMessages(role.id).collect { entities ->
                _uiState.value = _uiState.value.copy(
                    messages = entities.map {
                        AgentChatMessage(id = it.id, role = it.role, content = it.content)
                    }
                )
            }
        }

        // 监听该 agent 的 prompt 优化建议
        viewModelScope.launch {
            runtime.promptSuggestions.collect { suggestions ->
                val mySuggestion = suggestions[role.id] ?: ""
                _uiState.value = _uiState.value.copy(promptSuggestion = mySuggestion)
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (!apiKeyProvider.hasKey()) {
            _uiState.value = _uiState.value.copy(error = "请先在设置中配置 API Key")
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
                val role = _uiState.value.agentRole
                val ctx = MicContext(
                    traceId = "chat_$now",
                    causedBy = "",
                    round = 1,
                    phase = MeetingState.IDLE,
                    subject = text,
                    agentRole = role,
                    task = "direct_chat",
                )

                var fullContent = ""
                agentPool.callAgentStreaming(role, ctx).collect { delta ->
                    fullContent += delta
                    // Update placeholder in memory state
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.map {
                            if (it.id == placeholderId) it.copy(content = fullContent, isStreaming = true) else it
                        }
                    )
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
                val errMsg = "${e.javaClass.simpleName}: ${e.message ?: "未知错误，请检查网络和API配置"}"
                chatDao.insert(AgentChatMessageEntity(
                    id = placeholderId,
                    agentRole = currentRoleId,
                    role = "assistant",
                    content = "错误: $errMsg",
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
        val role = _uiState.value.agentRole
        val dir = File(appContext.filesDir, "prompts")
        dir.mkdirs()
        val file = File(dir, "${role.systemPromptKey}.txt")
        file.writeText(newPrompt)

        // No cache to clear — buildSystemPrompt reads file every time

        _uiState.value = _uiState.value.copy(
            systemPrompt = newPrompt,
            isEditingPrompt = false,
            promptSource = "本地文件",
        )
    }

    /** Reset prompt to asset default */
    fun resetPrompt() {
        val role = _uiState.value.agentRole
        val file = File(appContext.filesDir, "prompts/${role.systemPromptKey}.txt")
        if (file.exists()) file.delete()

        // No cache to clear
        val defaultPrompt = agentPool.getSystemPromptText(role)

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

        val role = _uiState.value.agentRole
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
        val role = _uiState.value.agentRole
        runtime.clearSuggestion(role.id)
        _uiState.value = _uiState.value.copy(promptSuggestion = "")
    }

    fun saveAgentConfig(config: LlmConfig) {
        viewModelScope.launch {
            apiKeyProvider.saveAgentConfig(_uiState.value.agentRole.id, config)
            _uiState.value = _uiState.value.copy(agentConfig = config, isUsingCustomConfig = true)
        }
    }

    fun resetAgentConfig() {
        viewModelScope.launch {
            val globalConfig = apiKeyProvider.getConfig()
            apiKeyProvider.clearAgentConfig(_uiState.value.agentRole.id)
            _uiState.value = _uiState.value.copy(agentConfig = globalConfig, isUsingCustomConfig = false)
        }
    }
}
