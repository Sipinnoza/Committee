package com.znliang.committee.engine.runtime

import android.content.Context
import android.util.Log
import com.znliang.committee.data.db.AgentEvolutionEntity
import com.znliang.committee.data.db.MeetingOutcomeEntity
import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.data.repository.EventRepository
import com.znliang.committee.data.repository.EvolutionRepository
import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.AgentPool
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.MaterialData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  AgentRuntime（v6 — 真流式 + SystemLlmService + 投票去重）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v7 变化（在v6基础上）：
 *    6. EvolvableAgent：Agent 可自我进化
 *       - 经验记忆：跨会议的经验持久化到 agent_evolution 表
 *       - 策略技能：可复用的策略模板存入 agent_skills 表
 *       - Prompt 自动进化：高优先级建议累积后自动改写 prompt
 *       - 会议结果追踪：投票正确率、自评分数持久化
 *    7. runLoop 注入经验：选中 Agent 后回忆相关经验 + 技能，注入 prompt
 *    8. reflectOnMeeting 升级：反思 → 学习 → 自动进化 → 技能提炼
 */
class AgentRuntime(
    private val agentPool: AgentPool,
    private var supervisor: SupervisorCapability,
    private var agents: List<Agent>,
    private val configProvider: suspend () -> LlmConfig,
    private val repository: EventRepository,
    private val evolutionRepo: EvolutionRepository,
    private val toolRegistry: DynamicToolRegistry,
    private val appContext: Context,
    private var preset: MeetingPreset,
) {
    companion object {
        private const val TAG = "AgentRuntime"
    }

    private var maxRounds = preset.mandateInt("max_rounds", 20)
    private var activationK = preset.mandateInt("activation_k", 3)
    private var summaryInterval = preset.mandateInt("summary_interval", 2)
    private var debateRounds = preset.mandateInt("debate_rounds", agents.size)
    private var hasSupervisor = preset.mandateBool("has_supervisor", true)
    private var supervisorFinalCall = preset.mandateBool("supervisor_final_call", true)
    private var consensusRequired = preset.mandateBool("consensus_required", false)
    private var strictAlternation = preset.mandateBool("strict_alternation", false)
    private var voteType = VoteType.valueOf(
        preset.mandateStr("vote_type", "binary").uppercase()
    )
    private var outputType = preset.mandateStr("output_type", "rating")

    /** 系统级 LLM 服务（无 Agent 身份），用于 Supervisor/反思等 */
    private val systemLlm = SystemLlmService(
        callStreaming = { config, sys, user -> agentPool.callSystemStreaming(config, sys, user) },
        configProvider = configProvider,
    )

    /** Prompt 自动进化引擎 */
    private val promptEvolver = PromptEvolver(systemLlm, appContext, evolutionRepo)

    /** 策略技能库 */
    private val skillLibrary = SkillLibrary(systemLlm, evolutionRepo)

    /** Dynamic evolver registry — creates GenericSelfEvolver for each agent */
    private var evolverRegistry: Map<String, AgentSelfEvolver> = buildEvolverRegistry()

    /** Find the tool-capable agent's role ID (for pre-meeting search) */
    private var toolAgentRoleId: String? = findToolAgentRoleId()

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

    /** Agent 自优化建议：roleId → 建议内容 */
    private val _promptSuggestions = MutableStateFlow<Map<String, String>>(emptyMap())
    val promptSuggestions: StateFlow<Map<String, String>> = _promptSuggestions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null
    private var currentTraceId: String = ""

    // ── 公开接口 ─────────────────────────────────────────────────

    fun startMeeting(subject: String, materials: List<MaterialRef> = emptyList()) {
        Log.i(TAG, "startMeeting called: isRunning=${_isRunning.value} subject=$subject materials=${materials.size}")
        if (_isRunning.value) return
        // Cancel any lingering job (e.g. post-meeting reflection from previous session)
        runJob?.cancel()
        currentTraceId = "mtg_${System.currentTimeMillis()}"
        _board.value = Blackboard(subject = subject, materials = materials, initialPhase = BoardPhase.ANALYSIS)
        synchronized(stateLock) {
            _speechesBacking.clear()
            _boardMessagesBacking.clear()
            _speeches.value = emptyList()
        }
        _runtimeLog.value = emptyList()
        _isPaused.value = false
        log("[Start] traceId=$currentTraceId subject=$subject materials=${materials.size}")

        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = currentTraceId,
                subject = subject,
                startTime = System.currentTimeMillis(),
                currentState = "ANALYSIS",
                currentRound = 1,
            ))
        }

        runJob = scope.launch {
            _isRunning.value = true
            try {
                toolRegistry.refresh()

                // ── 🔥 情报官预搜索（参考 Hermes 的 proactive info gathering） ──
                // 基于 Historical 信息缺口记忆，在会议开始前自动搜索补充
                preMeetingIntelSearch(subject)

                runLoop()
            } catch (_: CancellationException) {
                log("[Interrupted] Meeting cancelled")
                updateSessionFinished()
            } catch (e: Exception) {
                log("[Error] ${e.javaClass.simpleName}: ${e.message}")
                updateSessionFinished()
            }

            // ── 会后自我反思（在 runLoop 完成后执行，_isRunning 仍为 true 防并发） ──
            try {
                if (_board.value.messages.isNotEmpty()) {
                    reflectOnMeeting()
                }
            } catch (_: CancellationException) {
                // 会议被取消时不反思
            } catch (e: Exception) {
                log("[Reflect] Failed: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun cancelMeeting() {
        runJob?.cancel()
        runJob = null
        _board.value = _board.value.copy(finished = true, phase = BoardPhase.IDLE)
        _isRunning.value = false
        _isPaused.value = false
        // Mark session as cancelled (not completed)
        if (currentTraceId.isNotBlank()) {
            val b = _board.value
            scope.launch {
                repository.upsertSession(MeetingSessionEntity(
                    traceId = currentTraceId,
                    subject = b.subject,
                    startTime = System.currentTimeMillis(),
                    currentState = "CANCELLED",
                    currentRound = b.round,
                    rating = null,
                    isCompleted = false,
                ))
                repository.saveSpeeches(currentTraceId, _speeches.value)
            }
        }
    }

    // ── Human-in-the-Loop ─────────────────────────────────────

    fun pauseMeeting() {
        _isPaused.value = true
        log("[Human] Meeting paused")
    }

    fun resumeMeeting() {
        _isPaused.value = false
        log("[Human] Meeting resumed")
    }

    fun injectHumanMessage(content: String) {
        if (content.isBlank()) return
        val tags = inferHumanTags(content)
        addBoardMessage("human", content, tags)
        addSpeech(SpeechRecord(
            id = "human_${System.currentTimeMillis()}",
            agent = "human",
            content = content,
            summary = "",
            round = _board.value.round,
        ))
        log("[Human] Injected message: ${content.take(60)}...")
    }

    fun injectHumanVote(agree: Boolean, reason: String) {
        _board.value = _board.value.copy(
            votes = _board.value.votes + ("human" to BoardVote(
                role = "human",
                agree = agree,
                reason = reason,
                round = _board.value.round,
            ))
        )
        // Check consensus after human vote
        val b = _board.value
        if (b.hasConsensus(voteType) && !b.consensus) {
            _board.value = b.copy(consensus = true)
            log("[Consensus] Agree ${(b.agreeRatio() * 100).toInt()}%")
        }
        log("[Human] Vote: ${if (agree) "Agree" else "Disagree"} reason=$reason")
    }

    /** 用户设置 Agent 话语权重 */
    fun setAgentWeight(roleId: String, weight: Float) {
        val clamped = weight.coerceIn(0.1f, 3.0f)
        _board.value = _board.value.copy(
            userWeights = _board.value.userWeights + (roleId to clamped)
        )
        log("[Human] Set weight: $roleId = ${"%.1f".format(clamped)}")
    }

    /**
     * 用户覆写决策 — 推翻 Agent 结论，记录用户自己的判断
     * 体现「用户是决策主导者，Agent 是辅助工具」
     */
    fun overrideDecision(newRating: String, reason: String) {
        _board.value = _board.value.copy(
            userOverrideRating = newRating,
            userOverrideReason = reason,
        )
        log("[Human] Override: $newRating reason=${reason.take(60)}")
    }

    private fun inferHumanTags(content: String): List<String> {
        val result = mutableListOf<String>()
        val c = content.uppercase()
        if (c.contains("风险") || c.contains("RISK")) result.add("RISK")
        if (c.contains("看多") || c.contains("赞成") || c.contains("支持")) result.add("PRO")
        if (c.contains("看空") || c.contains("反对") || c.contains("质疑")) result.add("CON")
        if (c.contains("问题") || c.contains("QUESTION")) result.add("QUESTION")
        return result.ifEmpty { listOf("GENERAL") }
    }

    /**
     * 用户追问：针对特定 Agent 的发言提出问题
     * Agent 基于当前 blackboard 上下文 + 用户问题 进行回答
     *
     * 暂停主循环以保护 _board/_speeches 写入，防止与 runLoop 竞态
     */
    @Volatile private var followUpInProgress = false

    fun followUpQuestion(roleId: String, question: String) {
        if (question.isBlank()) return
        if (followUpInProgress) {
            log("[FollowUp] Already in progress, ignoring")
            return
        }
        followUpInProgress = true
        // 注入用户提问
        addBoardMessage("human", "[@$roleId] $question", listOf("QUESTION"))
        addSpeech(SpeechRecord(
            id = "human_${System.currentTimeMillis()}",
            agent = "human",
            content = "[@$roleId] $question",
            summary = "",
            round = _board.value.round,
        ))
        log("[Human] Follow-up to $roleId: ${question.take(60)}...")

        // 让目标 Agent 回答
        val agent = agents.find { it.role == roleId } ?: return
        val recordId = "followup_${System.currentTimeMillis()}"
        addSpeech(SpeechRecord(
            id = recordId,
            agent = roleId,
            content = "",
            summary = "",
            round = _board.value.round,
            isStreaming = true,
        ))

        // 暂时暂停主循环，防止竞态写入
        val wasPaused = _isPaused.value
        if (!wasPaused) _isPaused.value = true

        scope.launch {
            try {
                val board = _board.value
                val context = board.contextForAgent(agent)
                val prompt = """你是【${agent.displayName}】。用户针对你的发言提出了追问，请直接回答。

讨论主题：${board.subject}
${context}

用户追问：$question

请直接回答，200字以内。不要输出 SPEAK/VOTE/TAGS 等格式标记。"""

                val sb = StringBuilder()
                collectWithBatchedEmission(
                    flow = agentPool.callAgentStreamingByRoleId(
                        roleId,
                        MicContext(
                            traceId = "followup_${System.currentTimeMillis()}",
                            causedBy = "human",
                            round = board.round,
                            phase = MeetingState.PHASE1_DEBATE,
                            subject = board.subject,
                            agentRoleId = roleId,
                            task = "followup",
                        ),
                        systemPromptOverride = prompt,
                    ),
                    sb = sb,
                    recordId = recordId,
                    extractContent = { it },
                )

                val finalContent = sb.toString().trim()
                emitSpeechUpdate(recordId, finalContent, isStreaming = false)
                addBoardMessage(roleId, finalContent, listOf("GENERAL"))
                log("[FollowUp] $roleId responded: ${finalContent.take(80)}")
            } catch (e: Exception) {
                removeSpeech(recordId)
                log("[FollowUp] Error: ${e.message}")
            } finally {
                // 恢复之前的暂停状态
                if (!wasPaused) _isPaused.value = false
                followUpInProgress = false
            }
        }
    }

    /**
     * 从流式累积文本中提取 CONTENT 之后、VOTE/TAGS 之前的干净内容。
     * 流式过程中用户只看到 CONTENT 部分，避免暴露 SPEAK/REASONING 等元数据。
     */
    private fun extractStreamingContent(accumulated: String): String {
        // 寻找 CONTENT: 或 CONTENT：标记
        val contentIdx = accumulated.indexOfContentMarker()
        if (contentIdx < 0) return "" // CONTENT 尚未出现 → "思考中"

        val afterContent = accumulated.substring(contentIdx)
        // 截断 VOTE: / TAGS: 之后的部分
        val endIdx = afterContent.indexOfAny(
            listOf("VOTE:", "VOTE：", "TAGS:", "TAGS："),
            ignoreCase = true,
        )
        val clean = if (endIdx > 0) afterContent.substring(0, endIdx) else afterContent
        return clean.trimEnd()
    }

    private fun String.indexOfContentMarker(): Int {
        for (marker in listOf("CONTENT:", "CONTENT：")) {
            val idx = this.indexOf(marker, ignoreCase = true)
            if (idx >= 0) return idx + marker.length
        }
        return -1
    }

    /** Index-based speech update: modifies backing list in-place, emits snapshot */
    private fun emitSpeechUpdate(recordId: String, content: String, isStreaming: Boolean = true) {
        synchronized(stateLock) {
            val idx = _speechesBacking.indexOfFirst { it.id == recordId }
            if (idx < 0) return
            _speechesBacking[idx] = _speechesBacking[idx].copy(content = content, isStreaming = isStreaming)
            _speeches.value = _speechesBacking.toList()
        }
    }

    /**
     * Batched emission for streaming tokens.
     * Collects flow deltas into [sb], emits UI updates at most every [BATCH_MS] ms (~12fps).
     * Reduces StateFlow emissions from ~2000/response to ~25.
     */
    private suspend fun collectWithBatchedEmission(
        flow: Flow<String>,
        sb: StringBuilder,
        recordId: String,
        extractContent: (String) -> String,
    ) {
        val BATCH_MS = 80L
        var lastEmitTime = 0L
        flow.collect { delta ->
            sb.append(delta)
            val now = System.currentTimeMillis()
            if (now - lastEmitTime >= BATCH_MS) {
                lastEmitTime = now
                emitSpeechUpdate(recordId, extractContent(sb.toString()))
            }
        }
        // Final flush — ensure last tokens are visible
        emitSpeechUpdate(recordId, extractContent(sb.toString()))
    }

    /** Add a speech to the backing list and emit snapshot */
    private fun addSpeech(record: SpeechRecord) {
        synchronized(stateLock) {
            _speechesBacking.add(record)
            _speeches.value = _speechesBacking.toList()
        }
    }

    /** Remove a speech from the backing list by id and emit snapshot */
    private fun removeSpeech(recordId: String) {
        synchronized(stateLock) {
            _speechesBacking.removeAll { it.id == recordId }
            _speeches.value = _speechesBacking.toList()
        }
    }

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
        currentTraceId = session.traceId
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
        )
        log("[Recover] traceId=${session.traceId} speeches=${speeches.size}")
    }

    fun confirmExecution() {
        val b = _board.value
        if (b.phase == BoardPhase.EXECUTION) {
            _board.value = b.copy(finished = true, phase = BoardPhase.DONE)
        }
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
        currentTraceId = ""
    }

    fun destroy() {
        runJob?.cancel()
        scope.cancel()
    }

    // ── 核心循环 ─────────────────────────────────────────────────

    /**
     * 🔥 情报官预搜索（参考 Hermes 的 proactive info gathering）
     *
     * 在会议正式开始前，根据历史信息缺口记忆自动搜索补充信息：
     * 1. 从 tool-capable agent 的 evolver 加载历史缺口
     * 2. 基于标的 + 缺口构造搜索查询
     * 3. 通过 WebSearchService 执行搜索
     * 4. 搜索结果注入 blackboard 的 preGatheredInfo
     * 5. 情报官发言时会自动看到这些预收集的信息
     */
    private suspend fun preMeetingIntelSearch(subject: String) {
        try {
            val toolEvolver = toolAgentRoleId?.let { evolverRegistry[it] }
            val gapMemory = toolEvolver?.getPreMeetingMemory() ?: emptyList()
            if (gapMemory.isEmpty()) {
                log("[PreSearch] No historical info gaps, skipping")
                return
            }

            // 🔥 获取当前日期，确保搜索最新信息
            val today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日", java.util.Locale.CHINA))
            log("[PreSearch] Found ${gapMemory.size} historical info gaps, date=$today, starting pre-search...")

            // 构造搜索查询：标的 + 日期 + 缺口
            val topGaps = gapMemory.take(3)
            val searchResults = mutableListOf<String>()

            for (gap in topGaps) {
                val query = "$subject $today $gap"
                log("[PreSearch] Query: $query")
                try {
                    val results = toolRegistry.executeBuiltinTool("web_search",
                        """{"query":"$query"}"""
                    )
                    if (!results.startsWith("[error]") && !results.startsWith("[无")) {
                        searchResults.add("### 查询: $query\n$results")
                        log("[PreSearch] Success, result length=${results.length}")
                    } else {
                        log("[PreSearch] Query '$query' no valid results")
                    }
                } catch (e: Exception) {
                    log("[PreSearch] Query '$query' failed: ${e.message}")
                }
            }

            if (searchResults.isNotEmpty()) {
                // 注入到 blackboard 的 preGatheredInfo 字段
                val info = buildString {
                    appendLine("## 📡 会前自动收集的信息（基于历史缺口）")
                    appendLine("当前日期：$today")
                    appendLine("以下信息由情报官在会议开始前自动搜索获取，请参考：")
                    appendLine()
                    searchResults.forEach { appendLine(it); appendLine() }
                }
                _board.value = _board.value.copy(preGatheredInfo = info)
                log("[PreSearch] Injected ${searchResults.size} pre-search results into blackboard")
            }
        } catch (e: Exception) {
            log("[PreSearch] Failed: ${e.message}")
        }
    }

    private suspend fun runLoop() {
        var consecutiveSilentRounds = 0  // 熔断器：连续静默轮次（仅计网络/解析错误导致的静默）
        val CIRCUIT_BREAKER_THRESHOLD = 3 // 连续3轮无人发言即熔断
        var consecutiveChosenSilent = 0  // SPEAK:NO 导致的连续静默（区别于网络错误）

        while (!_board.value.finished) {
            // ── Human-in-the-Loop: 暂停检查（zero-cost suspend） ──
            if (_isPaused.value) {
                _isPaused.first { !it || _board.value.finished }
            }

            val board = _board.value

            if (board.round > maxRounds) {
                log("[SafetyValve] Exceeded $maxRounds rounds")
                finishWithRating(defaultRating())
                break
            }

            log("[Round ${board.round}] ────────────────────────────")

            // ── ① Summary Memory ──────────────────────────────────────
            if (board.round > 1 && board.round - board.lastSummaryRound >= summaryInterval) {
                updateSummary()
            }

            // ── ② Supervisor 结束判断（降频 + 动态门槛 + mandate 驱动） ─────
            // debate_rounds: 前 N 轮禁止结束判断
            val nonSupervisorMsgs = board.messages.count { it.role != "supervisor" }
            val minMsgThreshold = agents.size * 2  // 每个 agent 平均至少发言 2 次
            val minRoundThreshold = debateRounds   // mandate-driven minimum rounds

            // ── 硬性保障：必须有足够多不同参与者发言过，不能依赖 LLM 判断 ──
            val distinctSpeakers = board.messages
                .map { it.role }
                .filter { it != "supervisor" && it != "human" }
                .distinct().size
            val minDistinctSpeakers = maxOf(agents.size - 1, 2) // 至少 N-1 人或 2 人
            val hasEnoughSpeakers = distinctSpeakers >= minDistinctSpeakers

            if (hasSupervisor &&
                hasEnoughSpeakers &&
                board.round > minRoundThreshold &&
                nonSupervisorMsgs >= minMsgThreshold &&
                (board.round % 3 == 0 || board.consensus || board.round >= minRoundThreshold * 2)) {
                val shouldFinish = askSupervisorFinish(board)
                log("[Supervisor] shouldFinish=$shouldFinish (msgs=$nonSupervisorMsgs, minMsgs=$minMsgThreshold, minRounds=$minRoundThreshold, speakers=$distinctSpeakers/$minDistinctSpeakers)")
                if (shouldFinish) {
                    // consensus_required: override supervisor YES if no consensus
                    if (consensusRequired && !board.hasConsensus(voteType) && board.round < maxRounds - 1) {
                        log("[Consensus] Required but not reached, continuing discussion")
                    } else {
                        val rating = if (supervisorFinalCall) doRating() else doVoteMajorityRating()
                        finishWithRating(rating ?: defaultRating())
                        break
                    }
                }
            } else if (!hasSupervisor && hasEnoughSpeakers &&
                board.round > minRoundThreshold &&
                nonSupervisorMsgs >= minMsgThreshold) {
                // No supervisor: use vote majority to decide when to finish
                if (board.hasConsensus(voteType) || board.round >= minRoundThreshold * 2) {
                    log("[NoSupervisor] Finishing by vote majority")
                    val rating = doVoteMajorityRating()
                    finishWithRating(rating ?: defaultRating())
                    break
                }
            }

            // ── ③ Supervisor 点评（降频，仅当 has_supervisor=true） ──────
            if (hasSupervisor && shouldSupervisorComment(board)) {
                val comment = systemLlm.quickCall(supervisor.buildSupervisionPrompt(board))
                if (comment.isNotBlank() && comment.length > 10) {
                    addBoardMessage(supervisor.role, comment, emptyList())
                }
            }

            // ── ④ 加权选择 Agent ──────────────────────────────────
            val eligible = agents.filter { it.eligible(_board.value) }
            log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                log("[eligible] 无人有资格")
                advanceRound()
                continue
            }

            val scored = eligible.map { agent ->
                    val baseScore = agent.scoring(_board.value)
                    val userWeight = _board.value.userWeights[agent.role] ?: 1.0f

                    // ── 未发言 Agent 优先 bonus ──
                    val hasSpokeEver = _board.value.messages.any { it.role == agent.role }
                    val neverSpokeBonus = if (!hasSpokeEver) 5.0 else 0.0

                    // ── SPEAK:NO 连续惩罚处理 ──
                    // 统计连续 SPEAK:NO 次数（通过"被选中但没写入 board"来推断）
                    val totalTimesSelected = _board.value.round  // 粗略：可能被选中的最大次数
                    val timesSpoken = _board.value.messages.count { it.role == agent.role }
                    val silentRatio = if (totalTimesSelected > 0) 1.0 - (timesSpoken.toDouble() / totalTimesSelected) else 0.0
                    val silentBonus = if (hasSpokeEver && silentRatio > 0.5 && _board.value.round > 2) 3.0 else 0.0

                    agent to (baseScore + neverSpokeBonus + silentBonus) * userWeight
                }
                .sortedByDescending { it.second }
            scored.forEach { (agent, score) ->
                log("[score] ${agent.role} = ${"%.1f".format(score)}")
            }

            // ── ⑤ 取 top-K 并行发言（前3轮放大K值，SPEAK=NO 不消耗轮次） ────
            val effectiveK = if (board.round <= 3) {
                minOf(agents.size, activationK + 1)
            } else {
                activationK
            }

            // ── 严格交替模式（辩论赛）：强制按轮次交替选择，忽略 scoring ──
            val topK = if (strictAlternation && agents.size == 2 && board.round > 3) {
                val lastSpeaker = board.messages.lastOrNull {
                    it.role != "supervisor" && it.role != "human"
                }?.role
                val nextAgent = eligible.firstOrNull { it.role != lastSpeaker }
                    ?: eligible.first()
                log("[StrictAlternation] last=$lastSpeaker → next=${nextAgent.role}")
                listOf(nextAgent to 0.0)
            } else {
                scored.take(effectiveK)
            }
            var anySpoke = false
            var anyChosenSilent = false  // 是否有 agent 收到响应但选择 SPEAK:NO

            for ((picked, _) in topK) {
                if (_board.value.finished) break

                try {
                    var prompt = picked.buildUnifiedPrompt(_board.value)

                    // ── 确保所有参与者都有发言机会：如果还没够多人发言过，强制发言 ──
                    val currentDistinctSpeakers = _board.value.messages
                        .map { it.role }
                        .filter { it != "supervisor" && it != "human" }
                        .distinct().size
                    val hasNeverSpoken = !_board.value.messages.any { it.role == picked.role }
                    val needMoreSpeakers = currentDistinctSpeakers < agents.size
                    if (hasNeverSpoken && needMoreSpeakers) {
                        prompt += "\n\n🔴 重要：你还从未在本次讨论中发言！讨论需要所有参与者的声音。你必须发言（SPEAK: YES），这是强制要求。"
                    }

                    // ── 长期沉默提示：如果 agent 已有 2+ 轮没发言，注入提醒 ──
                    val lastSpokeRound = _board.value.messages.lastOrNull { it.role == picked.role }?.round ?: 0
                    if (lastSpokeRound > 0 && _board.value.round - lastSpokeRound >= 2) {
                        prompt += "\n\n⚠️ 你已经${_board.value.round - lastSpokeRound}轮没有发言了，请重新审视讨论进展，积极参与讨论。SPEAK: YES 是强烈建议。"
                    }

                    // ── ⑤½ 经验注入：回忆相关经验 + 技能 + 专属Evolver记忆 ──
                    if (picked is EvolvableAgent) {
                        try {
                            val experiences = picked.recallRelevantExperience(
                                _board.value, evolutionRepo.evolutionDao()
                            )
                            val skills = skillLibrary.findRelevantSkills(
                                picked.role, _board.value.subject,
                                _board.value.summary
                            )

                            // 🔥 统一使用各Agent的专属Evolver注入记忆
                            val evolver = evolverRegistry[picked.role]
                            var enrichedPrompt = prompt

                            if (experiences.isNotEmpty() || skills.isNotEmpty()) {
                                enrichedPrompt = picked.enrichPrompt(prompt, experiences, skills)
                                log("[进化] ${picked.role} 注入 ${experiences.size}条经验 + ${skills.size}个技能")
                            }

                            // 🔥 额外注入：各Agent专属的Evolver预会议记忆
                            if (evolver != null) {
                                val preMemory = evolver.getPreMeetingMemory()
                                if (preMemory.isNotEmpty()) {
                                    enrichedPrompt = enrichedPrompt + "\n\n## 🧠 专属进化记忆（从过去会议中学到的教训）\n" +
                                        preMemory.joinToString("\n") { "- $it" } +
                                        "\n请参考以上记忆来指导你的发言。"
                                    log("[进化] ${picked.role} 注入 ${preMemory.size}条专属记忆")
                                }
                            }

                            prompt = enrichedPrompt
                        } catch (e: Exception) {
                            log("[进化] ${picked.role} 经验注入失败: ${e.message}")
                        }
                    }

                    if (prompt.isBlank()) {
                        log("[${picked.role}] prompt 为空")
                        continue
                    }

                    val roleId = picked.role
                    val round = _board.value.round
                    val recordId = "sp_${System.currentTimeMillis()}"

                    // 创建流式占位记录
                    addSpeech(SpeechRecord(
                        id = recordId,
                        agent = roleId,
                        content = "",
                        summary = "",
                        round = round,
                        isStreaming = true,
                    ))

                    // 真流式：batched emission (~80ms/12fps) 替代逐 token 推送
                    val sb = StringBuilder()
                    // 将 MaterialRef 转换为 MaterialData 传给 AgentPool
                    val materialData = _board.value.materials
                        .filter { it.mimeType.startsWith("image/") && it.base64.isNotBlank() }
                        .map { MaterialData(mimeType = it.mimeType, base64 = it.base64, fileName = it.fileName) }
                    collectWithBatchedEmission(
                        flow = agentPool.callAgentStreamingByRoleId(
                            roleId,
                            MicContext(
                                traceId = "ag_${System.currentTimeMillis()}",
                                causedBy = "system",
                                round = round,
                                phase = MeetingState.PHASE1_DEBATE,
                                subject = _board.value.subject,
                                agentRoleId = roleId,
                                task = "decision",
                            ),
                            systemPromptOverride = prompt,
                            materials = materialData,
                        ),
                        sb = sb,
                        recordId = recordId,
                        extractContent = { extractStreamingContent(it) },
                    )

                    // 流结束，解析完整响应
                    val raw = sb.toString()
                    log("[${picked.role}] raw=${raw.take(300)}")
                    val response = UnifiedResponse.parse(raw, picked.canVote, voteType)
                    log("[${picked.role}] speak=${response.wantsToSpeak} vote=${response.voteBull}")

                    // ── SPEAK:NO 安全阀：如果 agent 从未发言过且有内容，强制视为发言 ──
                    val neverSpokenBefore = !_board.value.messages.any { it.role == picked.role }
                    val effectiveWantsToSpeak = response.wantsToSpeak ||
                        (neverSpokenBefore && response.content.isNotBlank())
                    if (!response.wantsToSpeak && effectiveWantsToSpeak) {
                        log("[${picked.role}] SPEAK:NO overridden — agent has never spoken, forcing speech")
                    }

                    if (effectiveWantsToSpeak && response.content.isNotBlank()) {
                        anySpoke = true

                        // 更新为干净的 content（去掉 SPEAK/VOTE/TAGS 元数据）
                        val idx = _speechesBacking.indexOfFirst { it.id == recordId }
                        if (idx >= 0) {
                            _speechesBacking[idx] = _speechesBacking[idx].copy(
                                content = response.content,
                                reasoning = response.reasoning,
                                isStreaming = false,
                            )
                            _speeches.value = _speechesBacking.toList()
                        }

                        // 写入 Board
                        val tags = response.normalizedTags.map { it.name }
                        addBoardMessage(picked.role, response.content, tags)

                        // ── 分歧检测：标注关键对立点 ──
                        detectAndMarkDivergence(picked.role, response.normalizedTags)

                        // 投票（Map 去重，携带扩展投票数据）
                        if (response.voteBull != null) {
                            _board.value = _board.value.copy(
                                votes = _board.value.votes + (picked.role to BoardVote(
                                    role = picked.role,
                                    agree = response.voteBull,
                                    round = _board.value.round,
                                    numericScore = response.numericScore,
                                    stanceLabel = response.stanceLabel,
                                ))
                            )
                            val voteLabel = when (voteType) {
                                VoteType.SCALE -> "Score=${response.numericScore}"
                                VoteType.MULTI_STANCE -> "Stance=${response.stanceLabel}"
                                VoteType.BINARY -> if (response.voteBull) "Agree" else "Disagree"
                            }
                            log("[Vote] ${picked.role} = $voteLabel")
                        }

                        // 共识
                        val b = _board.value
                        if (b.hasConsensus(voteType) && !b.consensus) {
                            _board.value = b.copy(consensus = true)
                            log("[Consensus] Agree ${(b.agreeRatio() * 100).toInt()}%")
                        }

                        updatePhase()

                        // 持久化
                        val finalRecord = _speeches.value.last { it.id == recordId }
                        persistSpeech(finalRecord)
                    } else {
                        // SPEAK=NO：移除占位记录，不消耗轮次
                        anyChosenSilent = true
                        removeSpeech(recordId)
                        log("[${picked.role}] SPEAK=NO（不消耗轮次）")
                    }
                } catch (e: Exception) {
                    log("[${picked.role}] 错误: ${e.message}")
                }
            }

            // ── ⑥ 熔断检测（区分 SPEAK:NO 与网络错误）──────────────
            if (anySpoke) {
                consecutiveSilentRounds = 0
                consecutiveChosenSilent = 0
                advanceRound()
            } else if (anyChosenSilent) {
                // Agent 收到了响应但选择不发言 — 非网络错误，不触发熔断
                consecutiveSilentRounds = 0
                consecutiveChosenSilent++
                if (consecutiveChosenSilent >= 5) {
                    // 5 轮连续 SPEAK:NO — 可能是 prompt 问题，注入强制发言提示
                    log("[CircuitBreak] $consecutiveChosenSilent rounds of SPEAK:NO, forcing next round")
                    consecutiveChosenSilent = 0
                }
                advanceRound()
            } else {
                // 真正的静默：没有 agent 响应（网络/解析错误）
                consecutiveSilentRounds++
                if (consecutiveSilentRounds >= CIRCUIT_BREAKER_THRESHOLD) {
                    log("[CircuitBreak] $CIRCUIT_BREAKER_THRESHOLD consecutive silent rounds (possible network issue), terminating early")
                    addBoardMessage("supervisor", "⚠️ Network connection error, unable to get AI response. Please check network and retry.", emptyList())
                    finishWithRating(defaultRating())
                    break
                }
                log("[CircuitBreak] Consecutive silent rounds $consecutiveSilentRounds/$CIRCUIT_BREAKER_THRESHOLD")
                advanceRound()
            }
        }
    }

    // ── Summary Memory ───────────────────────────────────────

    private suspend fun updateSummary() {
        val board = _board.value
        if (board.messages.size < 3) return
        log("[Summary] 生成讨论摘要（${board.messages.size}条消息）...")
        val prompt = supervisor.buildSummaryPrompt(board)
        val newSummary = systemLlm.quickCall(prompt)
        if (newSummary.isNotBlank()) {
            _board.value = board.copy(
                summary = newSummary,
                lastSummaryRound = board.round,
            )
            log("[Summary] 已更新（${newSummary.length}字）")
        }
    }

    // ── Supervisor 降频 ──────────────────────────────────────

    private fun shouldSupervisorComment(board: Blackboard): Boolean {
        if (board.round <= 1) return false
        if (board.messages.any { it.role == "supervisor" && it.round == board.round }) return false
        if (board.consensus) return false
        return board.messages.count { it.role != "supervisor" } >= 3
    }

    private suspend fun askSupervisorFinish(board: Blackboard): Boolean {
        val prompt = supervisor.buildFinishPrompt(board)
        val response = systemLlm.quickCall(prompt)
        val r = response.trim().uppercase()
        return r.startsWith("YES") || r.startsWith("Y")
    }

    // ── Board 操作（backing list 优化）───────────────────────────────────

    private fun addBoardMessage(role: String, content: String, rawTags: List<String>) {
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

    private fun updatePhase() {
        val b = _board.value
        val newPhase = b.inferPhase()
        if (b.phase != newPhase) {
            _board.value = b.copy(phase = newPhase)
            log("[Phase] ${b.phase} → $newPhase")
        }
    }

    private fun advanceRound() {
        if (!_board.value.finished) {
            _board.value = _board.value.copy(round = _board.value.round + 1)
        }
    }

    /**
     * 分歧检测 — 识别最近两条发言是否存在立场对立
     * 如果检测到对立观点，插入一条系统事件标注分歧焦点
     */
    private fun detectAndMarkDivergence(currentRole: String, currentTags: List<MsgTag>) {
        val board = _board.value
        // 找最近一条非当前 Agent 的发言
        val lastOther = board.messages
            .lastOrNull { it.role != currentRole && it.role != "supervisor" && it.role != "human" }
            ?: return

        val opposingPairs = listOf(
            MsgTag.PRO to MsgTag.CON,
            MsgTag.PRO to MsgTag.RISK,
        )

        for ((tagA, tagB) in opposingPairs) {
            val currentHasA = currentTags.contains(tagA)
            val currentHasB = currentTags.contains(tagB)
            val otherHasA = lastOther.normalizedTags.contains(tagA)
            val otherHasB = lastOther.normalizedTags.contains(tagB)

            if ((currentHasA && otherHasB) || (currentHasB && otherHasA)) {
                val divergenceNote = "Divergence: ${lastOther.role} vs $currentRole on ${tagA.name}/${tagB.name}"
                addSpeech(SpeechRecord(
                    id = "div_${System.currentTimeMillis()}",
                    agent = "system",
                    content = divergenceNote,
                    summary = "",
                    round = board.round,
                ))
                log("[Divergence] $divergenceNote")
                return // 每条发言最多标注一次
            }
        }
    }

    private suspend fun doRating(): String? {
        val prompt = supervisor.buildRatingPrompt(_board.value)
        val content = systemLlm.quickCall(prompt)
        return parseRating(content)
    }

    /** Vote-majority rating: pick the rating from ratingScale based on vote results */
    private fun doVoteMajorityRating(): String? {
        val board = _board.value
        return when (voteType) {
            VoteType.BINARY -> {
                if (board.agreeRatio() >= 0.5f) preset.ratingScale.firstOrNull()
                else preset.ratingScale.lastOrNull()
            }
            VoteType.SCALE -> {
                val avg = board.averageScore()
                // Map 1-10 score to ratingScale index
                val idx = ((1f - (avg - 1f) / 9f) * (preset.ratingScale.size - 1)).toInt()
                    .coerceIn(0, preset.ratingScale.size - 1)
                preset.ratingScale[idx]
            }
            VoteType.MULTI_STANCE -> {
                board.majorityStance() ?: preset.ratingScale.firstOrNull()
            }
        }
    }

    private fun defaultRating(): String = preset.ratingScale.getOrElse(preset.ratingScale.size / 2) { "Hold" }

    private fun finishWithRating(rating: String) {
        // 计算决策置信度
        val (confidence, breakdown) = calculateConfidence()

        _board.value = _board.value.copy(
            finished = true,
            finalRating = rating,
            phase = BoardPhase.RATING,
            decisionConfidence = confidence,
            confidenceBreakdown = breakdown,
        )
        log("[Rating] Final rating: $rating, confidence: $confidence%")
        if (agents.any { it.role == "executor" }) {
            _board.value = _board.value.copy(phase = BoardPhase.EXECUTION)
        }
        updateSessionFinished()
        // 异步评估各 Agent 贡献度
        scope.launch { scoreContributions() }
    }

    /**
     * 计算决策置信度（0-100）
     *
     * 基于三个维度：
     * 1. 共识度（40分）— 投票一致性
     * 2. 讨论充分度（35分）— 参与者数量、轮次、发言量
     * 3. 分歧解决度（25分）— 是否有未回应的质疑
     */
    private fun calculateConfidence(): Pair<Int, String> {
        val board = _board.value
        val breakdown = StringBuilder()
        var total = 0

        // ① 共识度（40分）
        val consensusScore = if (board.votes.isEmpty()) {
            10 // 没有投票，给基础分
        } else {
            val ratio = maxOf(board.agreeRatio(), board.disagreeRatio())
            (ratio * 40).toInt()
        }
        total += consensusScore
        breakdown.appendLine("Consensus: $consensusScore/40")

        // ② 讨论充分度（35分）
        val uniqueSpeakers = board.messages.map { it.role }.distinct()
            .filter { it != "supervisor" && it != "human" && it != "system" }.size
        val totalAgents = agents.size
        val participationRatio = if (totalAgents > 0) uniqueSpeakers.toFloat() / totalAgents else 0f
        val participationScore = (participationRatio * 15).toInt()

        val roundScore = minOf(board.round * 2, 10)
        val msgScore = minOf(board.messages.size, 10)
        val sufficiencyScore = participationScore + roundScore + msgScore
        total += minOf(sufficiencyScore, 35)
        breakdown.appendLine("Sufficiency: ${minOf(sufficiencyScore, 35)}/35")

        // ③ 分歧解决度（25分）
        val questions = board.messages.filter { it.normalizedTags.contains(MsgTag.QUESTION) }
        val answeredQuestions = questions.count { q ->
            board.messages.any { a -> a.round > q.round && a.role != q.role }
        }
        val divergenceResolved = if (board.consensus) 15 else 5
        val questionResolved = if (questions.isEmpty()) 10 else
            (answeredQuestions.toFloat() / questions.size * 10).toInt()
        val resolutionScore = minOf(divergenceResolved + questionResolved, 25)
        total += resolutionScore
        breakdown.appendLine("Resolution: $resolutionScore/25")

        return total.coerceIn(0, 100) to breakdown.toString().trim()
    }

    /**
     * 让 Supervisor 评估各 Agent 的发言贡献度
     */
    private suspend fun scoreContributions() {
        try {
            val board = _board.value
            val agentRoles = agents.map { it.role }.filter { roleId ->
                board.messages.any { it.role == roleId }
            }
            if (agentRoles.isEmpty()) return

            val allSpeeches = agentRoles.joinToString("\n\n") { roleId ->
                val msgs = board.messages.filter { it.role == roleId }
                "【$roleId】(${msgs.size}条发言)\n" +
                    msgs.joinToString("\n") { "R${it.round}: ${it.content.take(150)}" }
            }

            val prompt = """评估以下讨论参与者的发言质量。讨论主题：${board.subject}

$allSpeeches

对每个参与者打分（1-5分），格式：
ROLE:角色ID|INFO:信息增量分|LOGIC:论证逻辑分|INTERACT:互动质量分|BRIEF:一句话点评

评分标准：
- 信息增量(INFO)：是否带来新观点/新数据/新视角
- 论证逻辑(LOGIC)：论据是否充分、推理是否严谨
- 互动质量(INTERACT)：是否回应他人、是否有建设性交锋"""

            val result = systemLlm.quickCall(prompt)
            val scores = mutableMapOf<String, ContributionScore>()
            val rolePattern = Regex("""ROLE:\s*(\S+)\|INFO:\s*(\d)\|LOGIC:\s*(\d)\|INTERACT:\s*(\d)\|BRIEF:\s*(.+)""")
            for (match in rolePattern.findAll(result)) {
                val (roleId, info, logic, interact, brief) = match.destructured
                if (agentRoles.any { it == roleId }) {
                    scores[roleId] = ContributionScore(
                        roleId = roleId,
                        informationGain = info.toIntOrNull()?.coerceIn(1, 5) ?: 3,
                        logicQuality = logic.toIntOrNull()?.coerceIn(1, 5) ?: 3,
                        interactionQuality = interact.toIntOrNull()?.coerceIn(1, 5) ?: 3,
                        brief = brief.trim(),
                    )
                }
            }
            if (scores.isNotEmpty()) {
                _board.value = _board.value.copy(contributionScores = scores)
                log("[ContribScore] Scored ${scores.size} agents")
            }
        } catch (e: Exception) {
            log("[ContribScore] Error: ${e.message}")
        }
    }

    private fun parseRating(content: String): String? {
        val scalePattern = preset.ratingScale.joinToString("|") { Regex.escape(it) }
        val regex = Regex("""(?:Final Rating|最终评级|最终结论|结论).*?($scalePattern)""", RegexOption.IGNORE_CASE)
        return regex.find(content)?.groupValues?.getOrNull(1)
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        _runtimeLog.value = (_runtimeLog.value + msg).takeLast(200)
    }

    private fun updateSessionFinished() {
        if (currentTraceId.isBlank()) return
        val b = _board.value
        scope.launch {
            repository.upsertSession(MeetingSessionEntity(
                traceId = currentTraceId,
                subject = b.subject,
                startTime = System.currentTimeMillis(),
                currentState = b.phase.name,
                currentRound = b.round,
                rating = b.finalRating,
                isCompleted = b.finished,
            ))
            repository.saveSpeeches(currentTraceId, _speeches.value)
        }
    }

    private fun persistSpeech(speech: SpeechRecord) {
        if (currentTraceId.isBlank() || speech.isStreaming) return
        scope.launch {
            repository.saveSpeeches(currentTraceId, listOf(speech))
        }
    }

    // ── 会后自我反思 + 进化闭环 ──────────────────────────────────────

    /**
     * v7 完整进化闭环：
     *   1. 反思：每个 Agent 自评表现
     *   2. 学习：将经验持久化到 agent_evolution
     *   3. 追踪：记录会议结果到 meeting_outcomes
     *   4. 自动进化：高优先级建议累积后触发 PromptEvolver
     *   5. 技能提炼：从经验中提炼可复用策略
     */
    private suspend fun reflectOnMeeting() {
        val board = _board.value
        if (board.messages.isEmpty()) return

        val participatedRoles = board.messages.map { it.role }.distinct()
        log("[Reflect] 参与者: $participatedRoles")

        val suggestions = mutableMapOf<String, String>()

        for (roleId in participatedRoles) {
            try {
                val agent = agents.find { it.role == roleId } ?: continue
                val evolver = evolverRegistry[roleId]

                if (evolver != null) {
                    // ── 🔥 超级智能体反思路径：每个Agent走专属Evolver ──
                    try {
                        val reflection = evolver.reflect(board, currentTraceId)
                        if (reflection.isEmpty()) {
                            log("[Reflect] ${evolver.displayName()}: 无反思结果")
                            continue
                        }

                        log("[Reflect] ${evolver.displayName()}: ${reflection.summary.take(80)}")

                        // 记录建议
                        if (reflection.suggestion.isNotBlank()) {
                            suggestions[roleId] = buildString {
                                append("${reflection.summary}\n")
                                append("建议: ${reflection.suggestion}\n")
                                append("优先级: ${reflection.priority}")
                            }
                        }

                        // ── 追踪：记录会议结果 ──
                        val agentVote = board.votes[roleId]
                        val ratingPositive = preset.ratingScale.indexOf(board.finalRating ?: "").let { it in 0..1 }
                        evolutionRepo.saveOutcome(MeetingOutcomeEntity(
                            meetingTraceId = currentTraceId,
                            agentRole = roleId,
                            subject = board.subject,
                            finalRating = board.finalRating,
                            agentVote = if (agentVote != null) { if (agentVote.agree) "AGREE" else "DISAGREE" } else null,
                            voteCorrect = if (agentVote != null) {
                                (agentVote.agree && ratingPositive) || (!agentVote.agree && !ratingPositive)
                            } else null,
                            selfScore = 0f,
                            lessonsLearned = reflection.summary.take(200),
                        ))

                        // ── 自动进化：高优先级建议触发 PromptEvolver ──
                        if (reflection.priority == "HIGH" && reflection.suggestion.isNotBlank() && reflection.suggestion != "无需修改") {
                            try {
                                val currentPrompt = agentPool.getSystemPromptTextByRoleId(roleId)
                                    ?: preset.findRole(roleId)?.responsibility
                                    ?: "Generic agent"
                                val evolved = promptEvolver.tryAutoEvolve(
                                    roleId, currentPrompt, reflection.suggestion, currentTraceId
                                )
                                if (evolved) {
                                    log("[进化] 🧬 ${evolver.displayName()} Prompt 已自动进化！")
                                }
                            } catch (e: Exception) {
                                log("[进化] ${evolver.displayName()} 自动进化失败: ${e.message}")
                            }
                        }

                        // ── 技能提炼 ──
                        try {
                            val recentExps = evolutionRepo.getExperiencesByRole(roleId, 10)
                                .map { AgentExperience.fromEntity(it) }
                            val extracted = skillLibrary.tryExtractSkill(roleId, recentExps)
                            if (extracted) {
                                log("[技能] 📌 ${evolver.displayName()} 新策略已提炼")
                            }
                        } catch (e: Exception) {
                            log("[技能] ${evolver.displayName()} 提炼失败: ${e.message}")
                        }

                        log("[Reflect] ${evolver.displayName()}: 完整进化闭环完成")
                    } catch (e: Exception) {
                        log("[Reflect] ${roleId} Evolver反思失败: ${e.message}")
                    }
                } else {
                    // ── 无专属Evolver的Agent走通用反思路径 ──
                    val myMessages = board.messages.filter { it.role == roleId }
                    val reflection = generateReflection(agent, board, myMessages)
                    if (reflection.isBlank()) continue
                    suggestions[roleId] = reflection
                    log("[Reflect] ${agent.displayName}: 通用反思完成")
                }
            } catch (e: Exception) {
                log("[Reflect] $roleId 失败: ${e.message}")
            }
        }

        if (suggestions.isNotEmpty()) {
            _promptSuggestions.value = suggestions
            log("[Reflect] ${suggestions.size} 条优化建议已就绪")
        }
    }

    /** 解析反思 LLM 输出 */
    private data class ParsedReflection(
        val reflection: String,
        val suggestion: String,
        val priority: String,
    )

    private fun parseReflection(raw: String): ParsedReflection {
        var reflection = ""
        var suggestion = ""
        var priority = "MEDIUM"
        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("REFLECTION:", ignoreCase = true) ->
                    reflection = t.substringAfter(":").trim()
                t.startsWith("SUGGESTION:", ignoreCase = true) ->
                    suggestion = t.substringAfter(":").trim()
                t.startsWith("PRIORITY:", ignoreCase = true) -> {
                    val v = t.substringAfter(":").trim().uppercase()
                    if (v in listOf("HIGH", "MEDIUM", "LOW")) priority = v
                }
            }
        }
        return ParsedReflection(
            reflection = reflection.ifBlank { raw.take(200) },
            suggestion = suggestion,
            priority = priority,
        )
    }

    private suspend fun generateReflection(
        agent: Agent,
        board: Blackboard,
        myMessages: List<BoardMessage>,
    ): String {
        val currentPrompt = agentPool.getSystemPromptTextByRoleId(agent.role)
                ?: return ""

        val mySpeeches = myMessages.joinToString("\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(300)}"
        }

        val outcome = buildString {
            append("Final Rating: ${board.finalRating ?: "None"}\n")
            append("Consensus: ${if (board.consensus) "Yes" else "No"}\n")
            append("Total Rounds: ${board.round}\n")
            append("Total Speeches: ${board.messages.size}\n")
        }

        val reflectPrompt = """你是一个AI Agent的自我反思系统。

## 当前 System Prompt
$currentPrompt

## 标的
${board.subject}

## 我在会议中的发言（${agent.displayName}）
$mySpeeches

## 会议结果
$outcome

## 任务
请反思你在这个会议中的表现，分析你的 system prompt 是否需要改进。

输出格式（严格遵守）：
REFLECTION: （2-3句话总结你在会议中的表现优劣）
SUGGESTION: （具体的 prompt 改进建议，直接给出改进后的完整 prompt 关键段落，150字以内）
PRIORITY: HIGH / MEDIUM / LOW

注意：
- 只建议真正能改善决策质量的修改
- 不要建议增加角色定位，只改进分析框架和输出质量
- 如果当前 prompt 已经很好，SUGGESTION 写 "无需修改" """

        return systemLlm.quickCall(reflectPrompt)
    }

    /** 清除指定 agent 的优化建议（用户已处理） */
    fun clearSuggestion(roleId: String) {
        _promptSuggestions.value = _promptSuggestions.value - roleId
    }

    /** 清除所有建议 */
    fun clearAllSuggestions() {
        _promptSuggestions.value = emptyMap()
    }

    // ── Reconfiguration ─────────────────────────────────────────

    private fun buildEvolverRegistry(): Map<String, AgentSelfEvolver> = buildMap {
        for (agent in agents) {
            val presetRole = preset.findRole(agent.role)
            put(agent.role, GenericSelfEvolver(
                systemLlm = systemLlm,
                evolutionRepo = evolutionRepo,
                roleId = agent.role,
                roleDisplayName = agent.displayName,
                roleResponsibility = presetRole?.responsibility ?: agent.displayName,
            ))
        }
        val supervisorRole = preset.findRole(supervisor.role)
        put(supervisor.role, GenericSelfEvolver(
            systemLlm = systemLlm,
            evolutionRepo = evolutionRepo,
            roleId = supervisor.role,
            roleDisplayName = supervisor.displayName,
            roleResponsibility = supervisorRole?.responsibility ?: supervisor.displayName,
        ))
    }

    private fun findToolAgentRoleId(): String? =
        agents.filterIsInstance<GenericAgent>()
            .firstOrNull { it.canUseTools }?.role
            ?: agents.find { it.role.contains("intel") || it.role.contains("researcher") }?.role

    /**
     * Reconfigure the runtime with a new preset (e.g. when user switches presets in Settings).
     * Must only be called when no meeting is running.
     */
    fun reconfigure(newPreset: MeetingPreset) {
        if (_isRunning.value) {
            Log.w(TAG, "Cannot reconfigure while meeting is running")
            return
        }
        preset = newPreset
        maxRounds = newPreset.mandateInt("max_rounds", 20)
        activationK = newPreset.mandateInt("activation_k", 3)
        summaryInterval = newPreset.mandateInt("summary_interval", 2)
        debateRounds = newPreset.mandateInt("debate_rounds", newPreset.roles.size)
        hasSupervisor = newPreset.mandateBool("has_supervisor", true)
        supervisorFinalCall = newPreset.mandateBool("supervisor_final_call", true)
        consensusRequired = newPreset.mandateBool("consensus_required", false)
        strictAlternation = newPreset.mandateBool("strict_alternation", false)
        voteType = VoteType.valueOf(
            newPreset.mandateStr("vote_type", "binary").uppercase()
        )
        outputType = newPreset.mandateStr("output_type", "rating")

        val supervisorPresetRole = newPreset.roles.find { it.isSupervisor }
            ?: newPreset.roles.last()

        supervisor = GenericSupervisor(
            presetRole = supervisorPresetRole,
            ratingScale = newPreset.ratingScale,
            committeeLabel = newPreset.committeeLabel,
            preset = newPreset,
        )

        val newPromptStyle = newPreset.mandateStr("prompt_style", "debate")
        val newVoteType = try {
            VoteType.valueOf(newPreset.mandateStr("vote_type", "binary").uppercase())
        } catch (_: Exception) { VoteType.BINARY }
        agents = newPreset.roles
            .filter { it.id != supervisorPresetRole.id }
            .map { role ->
                GenericAgent(
                    presetRole = role,
                    systemPrompt = "",
                    canUseTools = role.canUseTools,
                    promptStyle = newPromptStyle,
                    voteType = newVoteType,
                    voteOptions = newPreset.ratingScale,
                )
            }

        evolverRegistry = buildEvolverRegistry()
        toolAgentRoleId = findToolAgentRoleId()
        Log.i(TAG, "Reconfigured to preset: ${newPreset.id} with ${agents.size} agents")
    }
}
