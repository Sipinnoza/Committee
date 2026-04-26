package com.znliang.committee.engine.runtime

import android.content.Context
import android.util.Log
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.data.repository.ActionRepository
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.data.repository.EvolutionRepository
import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.engine.LlmClient
import com.znliang.committee.engine.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  AgentRuntime — Facade（v8 — Phase 2 split）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v8 变化：
 *    - 实现 RuntimeContext 接口，持有所有可变状态
 *    - 核心循环委托给 MeetingOrchestrator
 *    - Human-in-the-Loop 委托给 HumanInteraction
 *    - 会后反思委托给 PostMeetingReflector
 *    - 本文件只保留：状态持有、公开 API、生命周期管理
 */
class AgentRuntime(
    agentPool: AgentPool,
    supervisor: SupervisorCapability,
    agents: List<Agent>,
    private val configProvider: suspend () -> LlmConfig,
    private val repository: EventRepository,
    evolutionRepo: EvolutionRepository,
    toolRegistry: DynamicToolRegistry,
    private val appContext: Context,
    preset: MeetingPreset,
    private val _actionRepo: ActionRepository,
) : RuntimeContext {

    companion object {
        private const val TAG = "AgentRuntime"
    }

    // ── 可变字段（reconfigure 时更新） ──────────────────────────────

    private val _agentPool: AgentPool = agentPool
    private var _supervisor: SupervisorCapability = supervisor
    private var _agents: List<Agent> = agents
    private var _preset: MeetingPreset = preset
    private var _evolutionRepo: EvolutionRepository = evolutionRepo
    private var _toolRegistry: DynamicToolRegistry = toolRegistry

    // ── Mandate-driven 配置（RuntimeContext 实现） ─────────────────

    private var _maxRounds = preset.mandateInt("max_rounds", 20)
    private var _activationK = preset.mandateInt("activation_k", 3)
    private var _summaryInterval = preset.mandateInt("summary_interval", 2)
    private var _debateRounds = preset.mandateInt("debate_rounds", agents.size)
    private var _hasSupervisor = preset.mandateBool("has_supervisor", true)
    private var _supervisorFinalCall = preset.mandateBool("supervisor_final_call", true)
    private var _consensusRequired = preset.mandateBool("consensus_required", false)
    private var _strictAlternation = preset.mandateBool("strict_alternation", false)
    private var _voteType = VoteType.valueOf(
        preset.mandateStr("vote_type", "binary").uppercase()
    )


    // ── 服务实例 ────────────────────────────────────────────────

    private val _systemLlm = SystemLlmService(
        callStreaming = { config, sys, user ->
            _agentPool.callSystemStreaming(config, sys, user)
        },
        configProvider = configProvider,
    )

    private val _promptEvolver = PromptEvolver(_systemLlm, appContext, _evolutionRepo)
    private val _skillLibrary = SkillLibrary(_systemLlm, _evolutionRepo)
    private var _evolverRegistry: Map<String, AgentSelfEvolver> = buildEvolverRegistry()
    private var _toolAgentRoleId: String? = findToolAgentRoleId()

    // ── 状态持有 ────────────────────────────────────────────────

    private val _board = MutableStateFlow(Blackboard())
    val board: StateFlow<Blackboard> = _board.asStateFlow()

    private val _speeches = MutableStateFlow<List<SpeechRecord>>(emptyList())
    val speeches: StateFlow<List<SpeechRecord>> = _speeches.asStateFlow()

    // Backing MutableLists — O(1) add, emit toList() snapshot to StateFlow
    // All access to _board, _speechesBacking, _boardMessagesBacking MUST hold this lock.
    private val stateLock = Any()
    private val _speechesBacking = mutableListOf<SpeechRecord>()
    private val _boardMessagesBacking = mutableListOf<BoardMessage>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _runtimeLog = MutableStateFlow<List<String>>(emptyList())
    val runtimeLog: StateFlow<List<String>> = _runtimeLog.asStateFlow()

    private val _promptSuggestions = MutableStateFlow<Map<String, String>>(emptyMap())
    val promptSuggestions: StateFlow<Map<String, String>> = _promptSuggestions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null
    private var _currentTraceId: String = ""
    private var _meetingStartTime: Long = 0L

    // ── 协作者 ──────────────────────────────────────────────────

    private val orchestrator = MeetingOrchestrator(this)
    private val humanInteraction = HumanInteraction(this, orchestrator)
    private val reflector = PostMeetingReflector(this)

    // ══════════════════════════════════════════════════════════════
    //  RuntimeContext 实现
    // ══════════════════════════════════════════════════════════════

    // ── 读状态 ──────────────────────────────────────────────────

    override val boardValue: Blackboard get() = _board.value
    override val speechesValue: List<SpeechRecord> get() = _speeches.value
    override val isPausedValue: Boolean get() = _isPaused.value
    override val currentTraceId: String get() = _currentTraceId
    override val promptSuggestionsValue: Map<String, String> get() = _promptSuggestions.value

    // ── 写状态 ──────────────────────────────────────────────────

    override fun updateBoard(transform: (Blackboard) -> Blackboard) {
        _board.update(transform)
    }

    override fun setIsPaused(value: Boolean) {
        _isPaused.value = value
    }

    override fun setPromptSuggestions(value: Map<String, String>) {
        _promptSuggestions.value = value
    }

    // ── Speech 操作 ─────────────────────────────────────────────

    override fun addSpeech(record: SpeechRecord) {
        synchronized(stateLock) {
            _speechesBacking.add(record)
            _speeches.value = _speechesBacking.toList()
        }
    }

    override fun removeSpeech(recordId: String) {
        synchronized(stateLock) {
            _speechesBacking.removeAll { it.id == recordId }
            _speeches.value = _speechesBacking.toList()
        }
    }

    override fun emitSpeechUpdate(recordId: String, content: String, isStreaming: Boolean) {
        synchronized(stateLock) {
            val idx = _speechesBacking.indexOfFirst { it.id == recordId }
            if (idx < 0) return
            _speechesBacking[idx] = _speechesBacking[idx].copy(content = content, isStreaming = isStreaming)
            _speeches.value = _speechesBacking.toList()
        }
    }

    override fun updateSpeechInBacking(recordId: String, transform: (SpeechRecord) -> SpeechRecord) {
        synchronized(stateLock) {
            val idx = _speechesBacking.indexOfFirst { it.id == recordId }
            if (idx >= 0) {
                _speechesBacking[idx] = transform(_speechesBacking[idx])
                _speeches.value = _speechesBacking.toList()
            }
        }
    }

    // ── Board 操作 ──────────────────────────────────────────────

    override fun addBoardMessage(role: String, content: String, rawTags: List<String>) {
        synchronized(stateLock) {
            val b = _board.value
            val newMsg = BoardMessage(
                role = role, content = content,
                round = b.round, rawTags = rawTags,
            )
            _boardMessagesBacking.add(newMsg)
            _board.value = b.copy(messages = _boardMessagesBacking.toList())
            log("[Board] +$role tags=$rawTags: ${content.take(60)}...")
        }
    }

    // ── 服务 ────────────────────────────────────────────────────

    override val agentPool: LlmClient get() = _agentPool
    override val toolRegistry: ToolExecutor get() = _toolRegistry
    override val systemLlm: SystemLlmService get() = _systemLlm
    override val supervisor: SupervisorCapability get() = _supervisor
    override val evolverRegistry: Map<String, AgentSelfEvolver> get() = _evolverRegistry
    override val skillLibrary: SkillLibrary get() = _skillLibrary
    override val evolutionRepo: EvolutionRepository get() = _evolutionRepo
    override val promptEvolver: PromptEvolver get() = _promptEvolver
    override val actionRepo: ActionRepository get() = _actionRepo

    // ── 配置 ────────────────────────────────────────────────────

    override val agents: List<Agent> get() = _agents
    override val voteType: VoteType get() = _voteType
    override val preset: MeetingPreset get() = _preset
    override val maxRounds: Int get() = _maxRounds
    override val summaryInterval: Int get() = _summaryInterval
    override val debateRounds: Int get() = _debateRounds
    override val hasSupervisor: Boolean get() = _hasSupervisor
    override val supervisorFinalCall: Boolean get() = _supervisorFinalCall
    override val consensusRequired: Boolean get() = _consensusRequired
    override val strictAlternation: Boolean get() = _strictAlternation
    override val activationK: Int get() = _activationK
    override val toolAgentRoleId: String? get() = _toolAgentRoleId

    // ── 工具方法 ────────────────────────────────────────────────

    override fun log(msg: String) {
        Log.d(TAG, msg)
        _runtimeLog.value = (_runtimeLog.value + msg).takeLast(200)
    }

    override fun launchInScope(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    override suspend fun awaitResumeOrFinish() {
        _isPaused.first { !it || _board.value.finished }
    }

    override fun updateSessionFinished() {
        if (_currentTraceId.isBlank()) return
        val b = _board.value
        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = _currentTraceId,
                subject = b.subject,
                startTime = _meetingStartTime,
                currentState = b.phase.name,
                currentRound = b.round,
                rating = b.finalRating,
                isCompleted = b.finished,
                summary = b.summary,
                consensus = b.consensus,
                decisionConfidence = b.decisionConfidence,
                confidenceBreakdown = b.confidenceBreakdown,
                votesJson = serializeVotes(b.votes),
                contributionsJson = serializeContributions(b.contributionScores),
                userOverrideRating = b.userOverrideRating,
                userOverrideReason = b.userOverrideReason,
                errorMessage = b.errorMessage,
                executionPlan = b.executionPlan,
            ))
            repository.saveSpeeches(_currentTraceId, _speeches.value)
        }
    }

    override fun persistSpeech(speech: SpeechRecord) {
        if (_currentTraceId.isBlank() || speech.isStreaming) return
        scope.launch {
            repository.saveSpeeches(_currentTraceId, listOf(speech))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  公开 API（ViewModel 调用）
    // ══════════════════════════════════════════════════════════════

    fun startMeeting(subject: String, materials: List<MaterialRef> = emptyList()) {
        Log.i(TAG, "startMeeting called: isRunning=${_isRunning.value} subject=$subject materials=${materials.size}")
        if (_isRunning.value) return
        // Cancel any lingering job (e.g. post-meeting reflection from previous session)
        runJob?.cancel()
        _currentTraceId = "mtg_${System.currentTimeMillis()}"
        _meetingStartTime = System.currentTimeMillis()
        _board.value = Blackboard(subject = subject, materials = materials, initialPhase = BoardPhase.ANALYSIS)
        synchronized(stateLock) {
            _speechesBacking.clear()
            _boardMessagesBacking.clear()
            _speeches.value = emptyList()
        }
        _runtimeLog.value = emptyList()
        _isPaused.value = false
        log("[Start] traceId=$_currentTraceId subject=$subject materials=${materials.size}")

        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = _currentTraceId,
                subject = subject,
                startTime = System.currentTimeMillis(),
                currentState = "ANALYSIS",
                currentRound = 1,
            ))
        }

        runJob = scope.launch {
            _isRunning.value = true
            try {
                _toolRegistry.refresh()

                // 情报官预搜索
                orchestrator.preMeetingIntelSearch(subject)

                // P0-4: 会前议程卡片 — 让用户了解会议结构
                emitPreMeetingAgenda(subject)

                // 核心循环
                orchestrator.runLoop()
            } catch (_: CancellationException) {
                log("[Interrupted] Meeting cancelled")
                updateSessionFinished()
            } catch (e: Exception) {
                log("[Error] ${e.javaClass.simpleName}: ${e.message}")
                updateSessionFinished()
            }

            // 会后自我反思
            try {
                if (_board.value.messages.isNotEmpty()) {
                    reflector.reflectOnMeeting()
                }
            } catch (_: CancellationException) {
                // 会议被取消时不反思
            } catch (e: Exception) {
                log("[Reflect] Failed: ${e.message}")
            }

            // 自动提取行动项
            try {
                if (_board.value.finished && _board.value.messages.isNotEmpty()) {
                    reflector.extractActionItems()
                }
            } catch (_: CancellationException) {
                // ignored
            } catch (e: Exception) {
                log("[ActionItems] Failed: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** P0-4: 会前议程 — 生成结构化会议说明 */
    private fun emitPreMeetingAgenda(subject: String) {
        val roles = agents.map { it.role }
        val roleList = roles.joinToString("、")
        val maxR = maxRounds
        val vt = when (voteType) {
            VoteType.BINARY -> "Binary (Agree/Disagree)"
            VoteType.SCALE -> "Scale (1-10)"
            VoteType.MULTI_STANCE -> "Multi-Stance"
        }
        val agendaText = buildString {
            appendLine("📋 Meeting Agenda")
            appendLine("Subject: $subject")
            appendLine("Participants: $roleList (${roles.size} roles)")
            appendLine("Max rounds: $maxR · Vote type: $vt")
            if (preset.ratingScale.isNotEmpty()) {
                appendLine("Rating scale: ${preset.ratingScale.joinToString(" / ")}")
            }
        }.trim()
        addSpeech(SpeechRecord(
            id = "agenda_${System.currentTimeMillis()}",
            agent = "system",
            content = agendaText,
            summary = "",
            round = 0,
            isAgendaEvent = true,
        ))
        log("[Agenda] Emitted pre-meeting agenda")
    }

    fun cancelMeeting() {
        runJob?.cancel()
        runJob = null
        _board.update { it.copy(finished = true, phase = BoardPhase.IDLE) }
        _isRunning.value = false
        _isPaused.value = false
        // Mark session as cancelled (not completed)
        if (_currentTraceId.isNotBlank()) {
            val b = _board.value
            scope.launch {
                repository.upsertSession(MeetingSessionEntity(
                    traceId = _currentTraceId,
                    subject = b.subject,
                    startTime = System.currentTimeMillis(),
                    currentState = "CANCELLED",
                    currentRound = b.round,
                    rating = null,
                    isCompleted = false,
                ))
                repository.saveSpeeches(_currentTraceId, _speeches.value)
            }
        }
    }

    // ── Human-in-the-Loop（委托给 HumanInteraction） ────────────────

    fun pauseMeeting() = humanInteraction.pauseMeeting()
    fun resumeMeeting() = humanInteraction.resumeMeeting()
    fun injectHumanMessage(content: String) = humanInteraction.injectHumanMessage(content)
    fun injectHumanVote(agree: Boolean, reason: String) = humanInteraction.injectHumanVote(agree, reason)
    fun followUpQuestion(roleId: String, question: String) = humanInteraction.followUpQuestion(roleId, question)
    fun setAgentWeight(roleId: String, weight: Float) = humanInteraction.setAgentWeight(roleId, weight)
    fun overrideDecision(newRating: String, reason: String) = humanInteraction.overrideDecision(newRating, reason)
    fun confirmExecution() = humanInteraction.confirmExecution()

    // ── 建议管理（委托给 PostMeetingReflector） ──────────────────────

    fun clearSuggestion(roleId: String) = reflector.clearSuggestion(roleId)
    fun clearAllSuggestions() = reflector.clearAllSuggestions()

    // ── 会话恢复 / 重置 ────────────────────────────────────────────

    /** 恢复历史 speeches 到 UI（用于 recoverSession） */
    fun restoreSpeeches(speeches: List<SpeechRecord>) {
        val restored = speeches.map { it.copy(isStreaming = false) }
        synchronized(stateLock) {
            _speechesBacking.clear()
            _speechesBacking.addAll(restored)
            _speeches.value = restored
        }
    }

    /** 从历史记录恢复完整会议状态（只展示，不重新运行） */
    fun recoverFromHistory(session: MeetingSessionEntity, speeches: List<SpeechRecord>) {
        _currentTraceId = session.traceId
        val restored = speeches.map { it.copy(isStreaming = false) }
        synchronized(stateLock) {
            _speechesBacking.clear()
            _speechesBacking.addAll(restored)
            _speeches.value = restored
        }
        _isRunning.value = false
        _board.value = Blackboard(
            subject = session.subject,
            round = session.currentRound,
            finished = session.isCompleted,
            finalRating = session.rating,
            phase = if (session.isCompleted) BoardPhase.DONE else BoardPhase.IDLE,
            summary = session.summary,
            consensus = session.consensus,
            decisionConfidence = session.decisionConfidence,
            confidenceBreakdown = session.confidenceBreakdown,
            votes = deserializeVotes(session.votesJson),
            contributionScores = deserializeContributions(session.contributionsJson),
            userOverrideRating = session.userOverrideRating,
            userOverrideReason = session.userOverrideReason,
            errorMessage = session.errorMessage,
            executionPlan = session.executionPlan,
        )
        log("[Recover] traceId=${session.traceId} speeches=${speeches.size}")
    }

    /** 重置到 IDLE（允许开始新会议） */
    fun resetToIdle() {
        runJob?.cancel()
        runJob = null
        synchronized(stateLock) {
            _board.value = Blackboard()
            _speechesBacking.clear()
            _boardMessagesBacking.clear()
            _speeches.value = emptyList()
        }
        _isRunning.value = false
        _isPaused.value = false
        _runtimeLog.value = emptyList()
        _currentTraceId = ""
    }

    fun destroy() {
        runJob?.cancel()
        scope.cancel()
    }

    // ── JSON serialization for votes/contributions ──────────────────

    private fun serializeVotes(votes: Map<String, BoardVote>): String {
        if (votes.isEmpty()) return ""
        return try {
            org.json.JSONArray().apply {
                votes.forEach { (_, v) ->
                    put(org.json.JSONObject().apply {
                        put("role", v.role)
                        put("agree", v.agree)
                        put("reason", v.reason)
                        put("round", v.round)
                        if (v.numericScore != null) put("numericScore", v.numericScore)
                        if (v.stanceLabel != null) put("stanceLabel", v.stanceLabel)
                    })
                }
            }.toString()
        } catch (_: Exception) { "" }
    }

    private fun deserializeVotes(json: String): Map<String, BoardVote> {
        if (json.isBlank()) return emptyMap()
        return try {
            val arr = org.json.JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val role = obj.getString("role")
                    put(role, BoardVote(
                        role = role,
                        agree = obj.getBoolean("agree"),
                        reason = obj.optString("reason", ""),
                        round = obj.getInt("round"),
                        numericScore = if (obj.has("numericScore")) obj.getInt("numericScore") else null,
                        stanceLabel = if (obj.has("stanceLabel")) obj.getString("stanceLabel") else null,
                    ))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    private fun serializeContributions(scores: Map<String, ContributionScore>): String {
        if (scores.isEmpty()) return ""
        return try {
            org.json.JSONArray().apply {
                scores.forEach { (_, s) ->
                    put(org.json.JSONObject().apply {
                        put("roleId", s.roleId)
                        put("informationGain", s.informationGain)
                        put("logicQuality", s.logicQuality)
                        put("interactionQuality", s.interactionQuality)
                        put("brief", s.brief)
                    })
                }
            }.toString()
        } catch (_: Exception) { "" }
    }

    private fun deserializeContributions(json: String): Map<String, ContributionScore> {
        if (json.isBlank()) return emptyMap()
        return try {
            val arr = org.json.JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val roleId = obj.getString("roleId")
                    put(roleId, ContributionScore(
                        roleId = roleId,
                        informationGain = obj.optInt("informationGain", 0),
                        logicQuality = obj.optInt("logicQuality", 0),
                        interactionQuality = obj.optInt("interactionQuality", 0),
                        brief = obj.optString("brief", ""),
                    ))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    // ── Reconfiguration ─────────────────────────────────────────

    private fun buildEvolverRegistry(): Map<String, AgentSelfEvolver> = buildMap {
        for (agent in _agents) {
            val presetRole = _preset.findRole(agent.role)
            put(agent.role, GenericSelfEvolver(
                systemLlm = _systemLlm,
                evolutionRepo = _evolutionRepo,
                roleId = agent.role,
                roleDisplayName = agent.displayName,
                roleResponsibility = presetRole?.responsibility ?: agent.displayName,
            ))
        }
        val supervisorRole = _preset.findRole(_supervisor.role)
        put(_supervisor.role, GenericSelfEvolver(
            systemLlm = _systemLlm,
            evolutionRepo = _evolutionRepo,
            roleId = _supervisor.role,
            roleDisplayName = _supervisor.displayName,
            roleResponsibility = supervisorRole?.responsibility ?: _supervisor.displayName,
        ))
    }

    private fun findToolAgentRoleId(): String? =
        _agents.filterIsInstance<GenericAgent>()
            .firstOrNull { it.canUseTools }?.role
            ?: _agents.find { it.role.contains("intel") || it.role.contains("researcher") }?.role

    /**
     * Reconfigure the runtime with a new preset (e.g. when user switches presets in Settings).
     * Must only be called when no meeting is running.
     */
    fun reconfigure(newPreset: MeetingPreset) {
        if (_isRunning.value) {
            Log.w(TAG, "Cannot reconfigure while meeting is running")
            return
        }
        _preset = newPreset
        _maxRounds = newPreset.mandateInt("max_rounds", 20)
        _activationK = newPreset.mandateInt("activation_k", 3)
        _summaryInterval = newPreset.mandateInt("summary_interval", 2)
        _debateRounds = newPreset.mandateInt("debate_rounds", newPreset.roles.size)
        _hasSupervisor = newPreset.mandateBool("has_supervisor", true)
        _supervisorFinalCall = newPreset.mandateBool("supervisor_final_call", true)
        _consensusRequired = newPreset.mandateBool("consensus_required", false)
        _strictAlternation = newPreset.mandateBool("strict_alternation", false)
        _voteType = VoteType.valueOf(
            newPreset.mandateStr("vote_type", "binary").uppercase()
        )


        val supervisorPresetRole = newPreset.roles.find { it.isSupervisor }
            ?: newPreset.roles.last()

        _supervisor = GenericSupervisor(
            presetRole = supervisorPresetRole,
            ratingScale = newPreset.ratingScale,
            committeeLabel = newPreset.committeeLabel,
            preset = newPreset,
        )

        val newPromptStyle = newPreset.mandateStr("prompt_style", "debate")
        val newVoteType = try {
            VoteType.valueOf(newPreset.mandateStr("vote_type", "binary").uppercase())
        } catch (_: Exception) { VoteType.BINARY }
        _agents = newPreset.roles
            .filter { it.id != supervisorPresetRole.id }
            .map { role ->
                GenericAgent(
                    presetRole = role,
                    canUseTools = role.canUseTools,
                    promptStyle = newPromptStyle,
                    voteType = newVoteType,
                    voteOptions = newPreset.ratingScale,
                )
            }

        _evolverRegistry = buildEvolverRegistry()
        _toolAgentRoleId = findToolAgentRoleId()
        Log.i(TAG, "Reconfigured to preset: ${newPreset.id} with ${_agents.size} agents")
    }
}
