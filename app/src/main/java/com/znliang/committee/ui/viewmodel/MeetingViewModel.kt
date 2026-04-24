package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.znliang.committee.R
import com.znliang.committee.data.db.DecisionActionEntity
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.data.repository.ActionRepository
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.data.repository.SkillRepository
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.domain.model.AppConfig
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.domain.model.PresetSkillCatalog
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.runtime.AgentRuntime
import com.znliang.committee.engine.runtime.DynamicToolRegistry
import com.znliang.committee.engine.runtime.BoardPhase
import com.znliang.committee.engine.runtime.BoardVote
import com.znliang.committee.engine.runtime.ContributionScore
import com.znliang.committee.engine.runtime.MaterialRef
import android.content.Context
import android.content.Intent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.znliang.committee.engine.report.DecisionReportGenerator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ── Split UI State: 4 independent StateFlows ──────────────────────

/** Updated every ~80ms during streaming — speeches, running/paused flags, logs */
data class StreamingState(
    val speeches: List<SpeechRecord> = emptyList(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val looperLogs: List<String> = emptyList(),
    val currentState: MeetingState = MeetingState.IDLE,
)

/** Updated per round/vote — board-level data */
data class BoardState(
    val subject: String = "",
    val boardPhase: BoardPhase = BoardPhase.IDLE,
    val boardRound: Int = 1,
    val boardConsensus: Boolean = false,
    val boardFinished: Boolean = false,
    val boardRating: String? = null,
    val boardSummary: String = "",
    val boardVotes: Map<String, BoardVote> = emptyMap(),
    val boardContribScores: Map<String, ContributionScore> = emptyMap(),
    val boardUserWeights: Map<String, Float> = emptyMap(),
    val boardConfidence: Int = 0,
    val boardConfidenceBreakdown: String = "",
    val boardUserOverride: String? = null,
    val boardUserOverrideReason: String = "",
)

/** Rarely updated — API key, LLM config, error */
data class ConfigState(
    val hasApiKey: Boolean = false,
    val llmConfig: LlmConfig = LlmConfig(),
    val error: String? = null,
)

/** Updated on session/action changes */
data class ActionState(
    val sessions: List<MeetingSessionEntity> = emptyList(),
    val pendingActions: List<DecisionActionEntity> = emptyList(),
    val appConfig: AppConfig = AppConfig(),
)

// Keep legacy MeetingUiState as a type alias for external consumers
// that may reference it (e.g. other screens)
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
    val boardVotes: Map<String, BoardVote> = emptyMap(),
    val boardContribScores: Map<String, ContributionScore> = emptyMap(),
    val boardUserWeights: Map<String, Float> = emptyMap(),
    val boardConfidence: Int = 0,
    val boardConfidenceBreakdown: String = "",
    val boardUserOverride: String? = null,
    val boardUserOverrideReason: String = "",
    val appConfig: AppConfig = AppConfig(),
    val pendingActions: List<DecisionActionEntity> = emptyList(),
)

@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val runtime: AgentRuntime,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val repository: EventRepository,
    private val presetConfig: MeetingPresetConfig,
    private val actionRepo: ActionRepository,
    private val skillRepo: SkillRepository,
    private val toolRegistry: DynamicToolRegistry,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // ── 4 independent StateFlows ──────────────────────────────────

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val _boardState = MutableStateFlow(BoardState())
    val boardState: StateFlow<BoardState> = _boardState.asStateFlow()

    private val _configState = MutableStateFlow(ConfigState())
    val configState: StateFlow<ConfigState> = _configState.asStateFlow()

    private val _actionState = MutableStateFlow(ActionState())
    val actionState: StateFlow<ActionState> = _actionState.asStateFlow()

    // Legacy combined state for screens that still use it
    private val _uiState = MutableStateFlow(MeetingUiState())
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    init {
        // ── StreamingState: speeches + isRunning + isPaused + logs ──
        viewModelScope.launch {
            combine(
                runtime.speeches,
                runtime.isRunning,
                runtime.isPaused,
                runtime.runtimeLog,
                runtime.board.map { it.boardPhaseToMeetingState(it.finished, it.phase) }.distinctUntilChanged(),
            ) { speeches, running, paused, logs, meetingState ->
                StreamingState(
                    speeches = speeches,
                    isRunning = running,
                    isPaused = paused,
                    looperLogs = logs.takeLast(100),
                    currentState = meetingState,
                )
            }.collect { _streamingState.value = it }
        }

        // ── BoardState: board changes ──
        viewModelScope.launch {
            runtime.board.collect { board ->
                _boardState.value = BoardState(
                    subject = board.subject,
                    boardPhase = board.phase,
                    boardRound = board.round,
                    boardConsensus = board.consensus,
                    boardFinished = board.finished,
                    boardRating = board.finalRating,
                    boardSummary = board.summary,
                    boardVotes = board.votes,
                    boardContribScores = board.contributionScores,
                    boardUserWeights = board.userWeights,
                    boardConfidence = board.decisionConfidence,
                    boardConfidenceBreakdown = board.confidenceBreakdown,
                    boardUserOverride = board.userOverrideRating,
                    boardUserOverrideReason = board.userOverrideReason,
                )
            }
        }

        // ── Legacy combined uiState (for generateReport etc.) ──
        viewModelScope.launch {
            combine(
                _streamingState,
                _boardState,
                _configState,
                _actionState,
            ) { streaming, board, config, action ->
                MeetingUiState(
                    currentState = streaming.currentState,
                    subject = board.subject,
                    speeches = streaming.speeches,
                    sessions = action.sessions,
                    looperLogs = streaming.looperLogs,
                    isRunning = streaming.isRunning,
                    isPaused = streaming.isPaused,
                    hasApiKey = config.hasApiKey,
                    llmConfig = config.llmConfig,
                    error = config.error,
                    boardPhase = board.boardPhase,
                    boardRound = board.boardRound,
                    boardConsensus = board.boardConsensus,
                    boardFinished = board.boardFinished,
                    boardRating = board.boardRating,
                    boardSummary = board.boardSummary,
                    boardVotes = board.boardVotes,
                    boardContribScores = board.boardContribScores,
                    boardUserWeights = board.boardUserWeights,
                    boardConfidence = board.boardConfidence,
                    boardConfidenceBreakdown = board.boardConfidenceBreakdown,
                    boardUserOverride = board.boardUserOverride,
                    boardUserOverrideReason = board.boardUserOverrideReason,
                    appConfig = action.appConfig,
                    pendingActions = action.pendingActions,
                )
            }.collect { _uiState.value = it }
        }

        // 监听历史 sessions
        viewModelScope.launch {
            repository.observeAllSessions().collect { sessions ->
                _actionState.value = _actionState.value.copy(sessions = sessions)
            }
        }

        // 会议结束后恢复快速决策临时 preset
        viewModelScope.launch {
            runtime.isRunning.collect { running ->
                if (!running && quickDecisionOriginalPreset != null) {
                    val original = quickDecisionOriginalPreset!!
                    quickDecisionOriginalPreset = null
                    runtime.reconfigure(original)
                    Log.i("MeetingVM", "Restored original preset after quick decision: ${original.id}")
                }
            }
        }

        // 初始化 API key 状态
        viewModelScope.launch {
            val config = apiKeyProvider.getConfig()
            _configState.value = _configState.value.copy(
                hasApiKey = config.apiKey.isNotBlank(),
                llmConfig = config,
            )
        }

        // 监听 preset 切换，自动 reconfigure runtime
        viewModelScope.launch {
            var isFirst = true
            presetConfig.activePresetFlow()
                .distinctUntilChanged()
                .collect { newPreset ->
                    if (isFirst) {
                        isFirst = false
                        if (newPreset.id != "investment_committee") {
                            runtime.reconfigure(newPreset)
                        }
                    } else {
                        runtime.reconfigure(newPreset)
                    }
                    seedRecommendedSkillsIfNeeded(newPreset.id)
                }
        }

        // 监听待执行的决策 action items
        viewModelScope.launch {
            actionRepo.observePending().collect { actions ->
                _actionState.value = _actionState.value.copy(pendingActions = actions)
            }
        }
    }

    fun requestMeeting(subject: String, materials: List<MaterialRef> = emptyList()) {
        Log.i("MeetingVM", "requestMeeting: subject=$subject materials=${materials.size}")
        if (subject.isBlank()) {
            _configState.value = _configState.value.copy(error = appContext.getString(R.string.error_enter_subject))
            return
        }
        if (!apiKeyProvider.hasKey()) {
            _configState.value = _configState.value.copy(error = appContext.getString(R.string.error_configure_api_key))
            return
        }
        runtime.startMeeting(subject, materials)
    }

    /** 快速决策结束后是否需要恢复原始 preset */
    private var quickDecisionOriginalPreset: com.znliang.committee.domain.model.MeetingPreset? = null

    fun requestQuickDecision(subject: String, materials: List<MaterialRef> = emptyList()) {
        Log.i("MeetingVM", "requestQuickDecision: subject=$subject")
        if (subject.isBlank()) {
            _configState.value = _configState.value.copy(error = appContext.getString(R.string.error_enter_subject))
            return
        }
        if (!apiKeyProvider.hasKey()) {
            _configState.value = _configState.value.copy(error = appContext.getString(R.string.error_configure_api_key))
            return
        }
        val originalPreset = presetConfig.getActivePreset()
        quickDecisionOriginalPreset = originalPreset
        val quickPreset = originalPreset.let { p ->
            p.copy(mandates = p.mandates + mapOf(
                "max_rounds" to "5",
                "activation_k" to "1",
                "summary_interval" to "3",
            ))
        }
        runtime.reconfigure(quickPreset)
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
    fun followUpQuestion(roleId: String, question: String) = runtime.followUpQuestion(roleId, question)
    fun setAgentWeight(roleId: String, weight: Float) = runtime.setAgentWeight(roleId, weight)
    fun overrideDecision(newRating: String, reason: String) = runtime.overrideDecision(newRating, reason)

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            apiKeyProvider.saveKey(key)
            _configState.value = _configState.value.copy(hasApiKey = key.isNotBlank(), error = null)
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
            _configState.value = _configState.value.copy(
                hasApiKey = config.apiKey.isNotBlank(),
                llmConfig = config,
                error = null,
            )
        }
    }

    fun clearError() {
        _configState.value = _configState.value.copy(error = null)
    }

    fun resetToIdle() {
        runtime.resetToIdle()
    }

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
            runtime.recoverFromHistory(session, speeches)
        }
    }

    // ── Decision Report Export ────────────────────────────────────

    fun generateReport(): File? {
        val streaming = _streamingState.value
        val board = _boardState.value
        val boardRaw = runtime.board.value
        val preset = presetConfig.getActivePreset()

        if (streaming.speeches.isEmpty()) return null

        val markdown = DecisionReportGenerator.generateMarkdown(
            subject = board.subject,
            presetName = preset.name,
            committeeLabel = preset.committeeLabel,
            roles = preset.roles.map { it.displayName },
            speeches = streaming.speeches,
            votes = boardRaw.votes,
            rating = board.boardRating,
            summary = board.boardSummary,
            startTime = System.currentTimeMillis() - (board.boardRound * 30_000L),
            totalRounds = board.boardRound,
            consensus = board.boardConsensus,
        )

        val reportsDir = File(appContext.filesDir, "reports").apply { mkdirs() }
        val fileName = "decision_${board.subject.take(20).replace(Regex("[^\\w]"), "_")}_${System.currentTimeMillis()}.md"
        val file = File(reportsDir, fileName)
        file.writeText(markdown)
        return file
    }

    fun generateShareText(): String {
        val board = _boardState.value
        val boardRaw = runtime.board.value
        val preset = presetConfig.getActivePreset()

        return DecisionReportGenerator.generateShareText(
            subject = board.subject,
            committeeLabel = preset.committeeLabel,
            rating = board.boardRating,
            summary = board.boardSummary,
            votes = boardRaw.votes,
            consensus = board.boardConsensus,
        )
    }

    fun createShareIntent(): Intent {
        val text = generateShareText()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "[Agentra] ${_boardState.value.subject}")
        }
    }

    // ── Decision Action Items ────────────────────────────────────

    fun addActionItem(title: String, description: String = "", assignee: String = "") {
        val board = _boardState.value
        viewModelScope.launch {
            actionRepo.insert(
                DecisionActionEntity(
                    traceId = "mtg_${board.subject.hashCode()}_${System.currentTimeMillis()}",
                    subject = board.subject,
                    title = title,
                    description = description,
                    assignee = assignee,
                )
            )
        }
    }

    fun updateActionStatus(actionId: Long, newStatus: String) {
        viewModelScope.launch {
            actionRepo.updateStatus(actionId, newStatus)
        }
    }

    fun deleteAction(actionId: Long) {
        viewModelScope.launch {
            actionRepo.delete(actionId)
        }
    }

    // ── Recommended Skill Seeding ──────────────────────────────────

    private suspend fun seedRecommendedSkillsIfNeeded(presetId: String) {
        if (presetConfig.isSkillsSeeded(presetId)) return
        val skills = PresetSkillCatalog.getSkillsForPreset(presetId)
        if (skills.isEmpty()) return

        var inserted = 0
        for (skill in skills) {
            val existing = skillRepo.getByName(skill.name)
            if (existing == null) {
                skillRepo.upsert(
                    SkillDefinitionEntity(
                        name = skill.name,
                        description = skill.description,
                        parameters = skill.parameters,
                        executionType = skill.executionType,
                        executionConfig = skill.executionConfig,
                    )
                )
                inserted++
            }
        }
        presetConfig.markSkillsSeeded(presetId)
        if (inserted > 0) {
            toolRegistry.refresh()
            Log.i("MeetingVM", "Seeded $inserted recommended skills for preset: $presetId")
        }
    }

    override fun onCleared() {
        super.onCleared()
        runtime.destroy()
    }
}

// Extension to derive MeetingState from board state
private fun com.znliang.committee.engine.runtime.Blackboard.boardPhaseToMeetingState(
    finished: Boolean,
    phase: BoardPhase,
): MeetingState = when {
    finished -> MeetingState.COMPLETED
    phase == BoardPhase.IDLE -> MeetingState.IDLE
    phase == BoardPhase.ANALYSIS -> MeetingState.PREPPING
    phase == BoardPhase.DEBATE -> MeetingState.PHASE1_DEBATE
    phase == BoardPhase.VOTE -> MeetingState.PHASE1_DEBATE
    phase == BoardPhase.RATING -> MeetingState.FINAL_RATING
    phase == BoardPhase.EXECUTION -> MeetingState.APPROVED
    phase == BoardPhase.DONE -> MeetingState.COMPLETED
    else -> MeetingState.IDLE
}
