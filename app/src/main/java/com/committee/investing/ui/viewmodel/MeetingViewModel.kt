package com.committee.investing.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.repository.EventRepository
import com.committee.investing.di.DataStoreApiKeyProvider
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.SpeechRecord
import com.committee.investing.engine.LlmConfig
import com.committee.investing.engine.runtime.AgentRuntime
import com.committee.investing.engine.runtime.BoardPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeetingUiState(
    val currentState: MeetingState = MeetingState.IDLE,
    val subject: String = "",
    val speeches: List<SpeechRecord> = emptyList(),
    val sessions: List<MeetingSessionEntity> = emptyList(),
    val looperLogs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val hasApiKey: Boolean = false,
    val llmConfig: LlmConfig = LlmConfig(),
    val error: String? = null,
    val boardPhase: BoardPhase = BoardPhase.IDLE,
    val boardRound: Int = 1,
    val boardConsensus: Boolean = false,
    val boardFinished: Boolean = false,
    val boardRating: String? = null,
    val boardSummary: String = "",
)

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val runtime: AgentRuntime,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val repository: EventRepository,
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
            ) { speeches, running, board, logs ->
                _uiState.value.copy(
                    speeches = speeches,
                    isRunning = running,
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
            _uiState.value = _uiState.value.copy(
                hasApiKey = apiKeyProvider.hasKey(),
                llmConfig = apiKeyProvider.getConfig(),
            )
        }
    }

    fun requestMeeting(subject: String) {
        Log.e("MeetingVM", "requestMeeting: subject=$subject hasKey=${apiKeyProvider.hasKey()}")
        if (subject.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "请输入标的名称")
            return
        }
        if (!apiKeyProvider.hasKey()) {
            _uiState.value = _uiState.value.copy(error = "请先在设置中配置 API Key")
            return
        }
        runtime.startMeeting(subject)
    }

    fun confirmExecution() {
        runtime.confirmExecution()
    }

    fun cancelMeeting() {
        runtime.cancelMeeting()
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            apiKeyProvider.saveKey(key)
            _uiState.value = _uiState.value.copy(hasApiKey = key.isNotBlank(), error = null)
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
            Log.e("MeetingVM", "recoverSession: traceId=$traceId subject=${session.subject} speeches=${speeches.size}")

            // 恢复到 runtime（只展示，不重新开会）
            runtime.recoverFromHistory(session, speeches)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runtime.destroy()
    }
}
