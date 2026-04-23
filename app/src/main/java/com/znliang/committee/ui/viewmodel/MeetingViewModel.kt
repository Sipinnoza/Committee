package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.znliang.committee.data.db.DecisionActionDao
import com.znliang.committee.data.db.DecisionActionEntity
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.domain.model.AppConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.runtime.AgentRuntime
import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.engine.runtime.MaterialRef
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.znliang.committee.engine.report.DecisionReportGenerator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class MeetingUiState(
    val currentState: MeetingState = MeetingState.IDLE,
    val subject: String = "",
    val speeches: List<SpeechRecord> = emptyList(),
    val sessions: List<MeetingSessionEntity> = emptyList(),
    val looperLogs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val hasApiKey: Boolean = false,
    val llmConfig: LlmConfig = LlmConfig(),
    val error: String? = null,
    val boardPhase: BoardPhase = BoardPhase.IDLE,
    val boardRound: Int = 1,
    val boardConsensus: Boolean = false,
    val boardFinished: Boolean = false,
    val boardRating: String? = null,
    val boardSummary: String = "",
    val appConfig: AppConfig = AppConfig(),
    val pendingActions: List<DecisionActionEntity> = emptyList(),
)

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val runtime: AgentRuntime,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val repository: EventRepository,
    private val presetConfig: MeetingPresetConfig,
    private val actionDao: DecisionActionDao,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    init {
        // 监听 runtime 状态
        viewModelScope.launch {
            combine(
                runtime.speeches,
                runtime.isRunning,
                runtime.board,
                runtime.runtimeLog,
                runtime.isPaused,
            ) { values ->
                val speeches = values[0] as List<SpeechRecord>
                val running = values[1] as Boolean
                val board = values[2] as com.znliang.committee.engine.runtime.Blackboard
                val logs = (values[3] as List<String>)
                val paused = values[4] as Boolean
                _uiState.value.copy(
                    speeches = speeches,
                    isRunning = running,
                    isPaused = paused,
                    looperLogs = logs.takeLast(100),
                    subject = board.subject,
                    boardPhase = board.phase,
                    boardRound = board.round,
                    boardConsensus = board.consensus,
                    boardFinished = board.finished,
                    boardRating = board.finalRating,
                    boardSummary = board.summary,
                    currentState = when {
                        board.finished -> MeetingState.COMPLETED
                        board.phase == BoardPhase.IDLE -> MeetingState.IDLE
                        board.phase == BoardPhase.ANALYSIS -> MeetingState.PREPPING
                        board.phase == BoardPhase.DEBATE -> MeetingState.PHASE1_DEBATE
                        board.phase == BoardPhase.VOTE -> MeetingState.PHASE1_DEBATE
                        board.phase == BoardPhase.RATING -> MeetingState.FINAL_RATING
                        board.phase == BoardPhase.EXECUTION -> MeetingState.APPROVED
                        board.phase == BoardPhase.DONE -> MeetingState.COMPLETED
                        else -> MeetingState.IDLE
                    },
                )
            }.collect { _uiState.value = it }
        }

        // 监听历史 sessions
        viewModelScope.launch {
            repository.observeAllSessions().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
            }
        }

        // 初始化 API key 状态
        viewModelScope.launch {
            val config = apiKeyProvider.getConfig()
            _uiState.value = _uiState.value.copy(
                hasApiKey = config.apiKey.isNotBlank(),
                llmConfig = config,
            )
        }

        // 监听 preset 切换，自动 reconfigure runtime
        viewModelScope.launch {
            presetConfig.activePresetFlow()
                .distinctUntilChangedBy { it.id }
                .drop(1) // skip initial value
                .collect { newPreset ->
                    runtime.reconfigure(newPreset)
                }
        }

        // 监听待执行的决策 action items
        viewModelScope.launch {
            actionDao.observePending().collect { actions ->
                _uiState.value = _uiState.value.copy(pendingActions = actions)
            }
        }
    }

    fun requestMeeting(subject: String, materials: List<MaterialRef> = emptyList()) {
        Log.i("MeetingVM", "requestMeeting: subject=$subject materials=${materials.size}")
        if (subject.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter a subject")
            return
        }
        if (!apiKeyProvider.hasKey()) {
            _uiState.value = _uiState.value.copy(error = "Please configure API Key in Settings")
            return
        }
        runtime.startMeeting(subject, materials)
    }

    fun confirmExecution() {
        runtime.confirmExecution()
    }

    fun cancelMeeting() {
        runtime.cancelMeeting()
    }

    fun pauseMeeting() = runtime.pauseMeeting()
    fun resumeMeeting() = runtime.resumeMeeting()
    fun injectHumanMessage(content: String) = runtime.injectHumanMessage(content)
    fun injectHumanVote(agree: Boolean, reason: String) = runtime.injectHumanVote(agree, reason)

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            apiKeyProvider.saveKey(key)
            _uiState.value = _uiState.value.copy(hasApiKey = key.isNotBlank(), error = null)
        }
    }

    fun saveTavilyKey(key: String) {
        viewModelScope.launch {
            apiKeyProvider.saveTavilyKey(key)
        }
    }

    fun saveLlmConfig(config: LlmConfig) {
        viewModelScope.launch {
            apiKeyProvider.saveConfig(config)
            _uiState.value = _uiState.value.copy(
                hasApiKey = config.apiKey.isNotBlank(),
                llmConfig = config,
                error = null,
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** 重置到 IDLE 状态，允许开始新会议 */
    fun resetToIdle() {
        runtime.resetToIdle()
    }

    /** 获取 runtime 的 prompt 优化建议 Flow */
    fun promptSuggestionsFlow() = runtime.promptSuggestions

    // Session history
    private val _sessionSpeeches = MutableStateFlow<Map<String, List<SpeechRecord>>>(emptyMap())
    val sessionSpeeches: StateFlow<Map<String, List<SpeechRecord>>> = _sessionSpeeches.asStateFlow()

    fun loadSessionSpeeches(traceId: String) {
        viewModelScope.launch {
            val speeches = repository.getSpeechesByTrace(traceId)
            _sessionSpeeches.value = _sessionSpeeches.value + (traceId to speeches)
        }
    }

    fun recoverSession(traceId: String) {
        viewModelScope.launch {
            val sessions = repository.observeAllSessions().first()
            val session = sessions.find { it.traceId == traceId } ?: return@launch
            val speeches = repository.getSpeechesByTrace(traceId)
            Log.i("MeetingVM", "recoverSession: traceId=$traceId subject=${session.subject} speeches=${speeches.size}")

            // 恢复到 runtime（只展示，不重新开会）
            runtime.recoverFromHistory(session, speeches)
        }
    }

    // ── Decision Report Export ────────────────────────────────────

    /**
     * 生成 Markdown 决策报告并返回文件路径
     */
    fun generateReport(): File? {
        val state = _uiState.value
        val board = runtime.board.value
        val preset = presetConfig.getActivePreset()

        if (state.speeches.isEmpty()) return null

        val markdown = DecisionReportGenerator.generateMarkdown(
            subject = state.subject,
            presetName = preset.name,
            committeeLabel = preset.committeeLabel,
            roles = preset.roles.map { it.displayName },
            speeches = state.speeches,
            votes = board.votes,
            rating = state.boardRating,
            summary = state.boardSummary,
            startTime = System.currentTimeMillis() - (state.boardRound * 30_000L), // estimate
            totalRounds = state.boardRound,
            consensus = state.boardConsensus,
        )

        val reportsDir = File(appContext.filesDir, "reports").apply { mkdirs() }
        val fileName = "decision_${state.subject.take(20).replace(Regex("[^\\w]"), "_")}_${System.currentTimeMillis()}.md"
        val file = File(reportsDir, fileName)
        file.writeText(markdown)
        return file
    }

    /**
     * 生成分享文本（简短摘要）
     */
    fun generateShareText(): String {
        val state = _uiState.value
        val board = runtime.board.value
        val preset = presetConfig.getActivePreset()

        return DecisionReportGenerator.generateShareText(
            subject = state.subject,
            committeeLabel = preset.committeeLabel,
            rating = state.boardRating,
            summary = state.boardSummary,
            votes = board.votes,
            consensus = state.boardConsensus,
        )
    }

    /**
     * 创建分享 Intent（纯文本快速分享）
     */
    fun createShareIntent(): Intent {
        val text = generateShareText()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "[Agentra] ${_uiState.value.subject}")
        }
    }

    // ── Decision Action Items ────────────────────────────────────

    /** 添加 action item */
    fun addActionItem(title: String, description: String = "", assignee: String = "") {
        val state = _uiState.value
        viewModelScope.launch {
            actionDao.insert(
                DecisionActionEntity(
                    traceId = runtime.board.value.let { "mtg_${state.subject.hashCode()}_${System.currentTimeMillis()}" },
                    subject = state.subject,
                    title = title,
                    description = description,
                    assignee = assignee,
                )
            )
        }
    }

    /** 更新 action item 状态 */
    fun updateActionStatus(actionId: Long, newStatus: String) {
        viewModelScope.launch {
            actionDao.updateStatus(actionId, newStatus)
        }
    }

    /** 删除 action item */
    fun deleteAction(actionId: Long) {
        viewModelScope.launch {
            actionDao.delete(actionId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runtime.destroy()
    }
}
