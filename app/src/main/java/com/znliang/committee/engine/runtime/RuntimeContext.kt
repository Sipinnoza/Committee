package com.znliang.committee.engine.runtime

import com.znliang.committee.data.repository.EvolutionRepository
import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.LlmClient

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  RuntimeContext — 协作者访问 AgentRuntime 状态的接口
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  所有 3 个协作者（MeetingOrchestrator、HumanInteraction、
 *  PostMeetingReflector）通过此接口访问 AgentRuntime 的内部状态。
 *  这避免了将协作者设为 inner class 的紧耦合。
 */
interface RuntimeContext {

    // ── 依赖 ──────────────────────────────────────────────────

    val agentPool: LlmClient
    val preset: MeetingPreset
    val agents: List<Agent>
    val supervisor: SupervisorCapability
    val systemLlm: SystemLlmService
    val evolutionRepo: EvolutionRepository
    val toolRegistry: ToolExecutor
    val promptEvolver: PromptEvolver
    val skillLibrary: SkillLibrary
    val evolverRegistry: Map<String, AgentSelfEvolver>
    val toolAgentRoleId: String?

    // ── 会议配置（mandate 驱动）─────────────────────────────────

    val maxRounds: Int
    val activationK: Int
    val summaryInterval: Int
    val debateRounds: Int
    val hasSupervisor: Boolean
    val supervisorFinalCall: Boolean
    val consensusRequired: Boolean
    val strictAlternation: Boolean
    val voteType: VoteType

    // ── 状态读取 ──────────────────────────────────────────────

    val boardValue: Blackboard
    val speechesValue: List<SpeechRecord>
    val isPausedValue: Boolean
    val currentTraceId: String
    val promptSuggestionsValue: Map<String, String>

    // ── 状态写入 ──────────────────────────────────────────────

    fun updateBoard(transform: (Blackboard) -> Blackboard)
    fun setIsPaused(value: Boolean)
    fun setPromptSuggestions(value: Map<String, String>)

    // ── Speech 操作 ─────────────────────────────────────────

    fun addSpeech(record: SpeechRecord)
    fun removeSpeech(recordId: String)
    fun emitSpeechUpdate(recordId: String, content: String, isStreaming: Boolean = true)

    /** In-place update of a speech record in the backing list (e.g., finalize content + reasoning) */
    fun updateSpeechInBacking(recordId: String, transform: (SpeechRecord) -> SpeechRecord)

    // ── Board 操作 ──────────────────────────────────────────

    fun addBoardMessage(role: String, content: String, rawTags: List<String>)

    // ── 日志 ──────────────────────────────────────────────────

    fun log(msg: String)

    // ── 持久化 ──────────────────────────────────────────────

    fun updateSessionFinished()
    fun persistSpeech(speech: SpeechRecord)

    // ── 协程 ──────────────────────────────────────────────────

    fun launchInScope(block: suspend () -> Unit)

    // ── Human-in-the-loop suspend ────────────────────────────

    /** Suspends until isPaused becomes false or board is finished. */
    suspend fun awaitResumeOrFinish()
}
