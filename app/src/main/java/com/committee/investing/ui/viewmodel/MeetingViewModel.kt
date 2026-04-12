package com.committee.investing.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.committee.investing.data.db.MeetingSessionEntity
import com.committee.investing.data.repository.EventRepository
import com.committee.investing.di.DataStoreApiKeyProvider
import com.committee.investing.domain.model.*
import com.committee.investing.engine.CommitteeLooper
import com.committee.investing.engine.LlmConfig
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
)

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val looper: CommitteeLooper,
    private val eventRepository: EventRepository,
    private val apiKeyProvider: DataStoreApiKeyProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    init {
        // Merge all observable flows into uiState
        viewModelScope.launch {
            combine(
                looper.currentState,
                looper.speeches,
                looper.isRunning,
                eventRepository.observeAllSessions(),
            ) { state, speeches, running, sessions ->
                _uiState.value.copy(
                    currentState = state,
                    speeches = speeches,
                    isRunning = running,
                    sessions = sessions,
                    hasApiKey = apiKeyProvider.hasKey(),
                    llmConfig = apiKeyProvider.getConfig(),
                )
            }.collect { _uiState.value = it }
        }

        viewModelScope.launch {
            looper.looperLog.collect { msg ->
                _uiState.value = _uiState.value.copy(
                    looperLogs = (_uiState.value.looperLogs + msg).takeLast(200)
                )
            }
        }

        startLooper()
    }

    private fun startLooper() {
        looper.start()
    }

    fun requestMeeting(subject: String) {
        if (subject.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "请输入标的名称")
            return
        }
        if (!apiKeyProvider.hasKey()) {
            _uiState.value = _uiState.value.copy(error = "请先在设置中配置 API Key")
            return
        }
        viewModelScope.launch {
            runCatching { looper.requestMeeting(subject) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun confirmExecution() {
        viewModelScope.launch { looper.confirmExecution() }
    }

    fun cancelMeeting() {
        viewModelScope.launch { looper.cancelMeeting() }
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

    fun recoverSession(traceId: String) {
        viewModelScope.launch { looper.recover(traceId) }
    }

    private val _sessionSpeeches = MutableStateFlow<Map<String, List<com.committee.investing.domain.model.SpeechRecord>>>(emptyMap())
    val sessionSpeeches: StateFlow<Map<String, List<com.committee.investing.domain.model.SpeechRecord>>> = _sessionSpeeches.asStateFlow()

    fun loadSessionSpeeches(traceId: String) {
        viewModelScope.launch {
            val speeches = eventRepository.getSpeechesByTrace(traceId)
            _sessionSpeeches.value = _sessionSpeeches.value + (traceId to speeches)
        }
    }

    override fun onCleared() {
        super.onCleared()
        looper.stop()
    }
}
