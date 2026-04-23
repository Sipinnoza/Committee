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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var activationK = preset.mandateInt("activation_k", 2)
    private var summaryInterval = preset.mandateInt("summary_interval", 2)

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
        currentTraceId = "mtg_${System.currentTimeMillis()}"
        _board.value = Blackboard(subject = subject, materials = materials)
        _speeches.value = emptyList()
        _runtimeLog.value = emptyList()
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
            } finally {
                _isRunning.value = false
            }

            // ── 会后自我反思（在 runLoop 完成后执行，无竞态） ────────
            try {
                if (_board.value.messages.isNotEmpty()) {
                    reflectOnMeeting()
                }
            } catch (_: CancellationException) {
                // 会议被取消时不反思
            } catch (e: Exception) {
                log("[Reflect] Failed: ${e.message}")
            }
        }
    }

    fun cancelMeeting() {
        runJob?.cancel()
        _board.value = _board.value.copy(finished = true, phase = BoardPhase.IDLE)
        _isRunning.value = false
        _isPaused.value = false
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
        _speeches.value = _speeches.value + SpeechRecord(
            id = "human_${System.currentTimeMillis()}",
            agent = "human",
            content = content,
            summary = "",
            round = _board.value.round,
        )
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
        if (b.hasConsensus() && !b.consensus) {
            _board.value = b.copy(consensus = true)
            log("[Consensus] Bull ${(b.bullRatio() * 100).toInt()}%")
        }
        log("[Human] Vote: ${if (agree) "Agree" else "Disagree"} reason=$reason")
    }

    private fun inferHumanTags(content: String): List<String> {
        val result = mutableListOf<String>()
        val c = content.uppercase()
        if (c.contains("风险") || c.contains("RISK")) result.add("RISK")
        if (c.contains("看多") || c.contains("BULL") || c.contains("赞成")) result.add("PRO")
        if (c.contains("看空") || c.contains("BEAR") || c.contains("反对")) result.add("CON")
        if (c.contains("问题") || c.contains("QUESTION")) result.add("QUESTION")
        return result.ifEmpty { listOf("GENERAL") }
    }

    /** 恢复历史 speeches 到 UI（用于 recoverSession） */
    fun restoreSpeeches(speeches: List<SpeechRecord>) {
        _speeches.value = speeches.map { it.copy(isStreaming = false) }
    }

    /** 从历史记录恢复完整会议状态（只展示，不重新运行） */
    fun recoverFromHistory(session: MeetingSessionEntity, speeches: List<SpeechRecord>) {
        currentTraceId = session.traceId
        _speeches.value = speeches.map { it.copy(isStreaming = false) }
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
        _board.value = Blackboard()
        _speeches.value = emptyList()
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
        var consecutiveSilentRounds = 0  // 熔断器：连续静默轮次
        val CIRCUIT_BREAKER_THRESHOLD = 3 // 连续3轮无人发言即熔断

        while (!_board.value.finished) {
            // ── Human-in-the-Loop: 暂停检查 ──
            while (_isPaused.value && !_board.value.finished) {
                kotlinx.coroutines.delay(200)
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

            // ── ② Supervisor 结束判断（降频 + 最低发言门槛） ─────
            val nonSupervisorMsgs = board.messages.count { it.role != "supervisor" }
            if (nonSupervisorMsgs >= 4 && (board.round % 3 == 0 || board.consensus || board.round >= 6)) {
                val shouldFinish = askSupervisorFinish(board)
                log("[Supervisor] shouldFinish=$shouldFinish (msgs=$nonSupervisorMsgs)")
                if (shouldFinish) {
                    val rating = doRating()
                    finishWithRating(rating ?: defaultRating())
                    break
                }
            }

            // ── ③ Supervisor 点评（降频） ──────────────────────────
            if (shouldSupervisorComment(board)) {
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

            val scored = eligible.map { it to it.scoring(_board.value) }
                .sortedByDescending { it.second }
            scored.forEach { (agent, score) ->
                log("[score] ${agent.role} = ${"%.1f".format(score)}")
            }

            // ── ⑤ 取 top-2 并行发言（SPEAK=NO 不消耗轮次） ────
            val topK = scored.take(activationK)
            var anySpoke = false

            for ((picked, _) in topK) {
                if (_board.value.finished) break

                try {
                    var prompt = picked.buildUnifiedPrompt(_board.value)

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
                    _speeches.value = _speeches.value + SpeechRecord(
                        id = recordId,
                        agent = roleId,
                        content = "",
                        summary = "",
                        round = round,
                        isStreaming = true,
                    )

                    // 真流式：delta 逐条推送到 UI
                    val sb = StringBuilder()
                    // 将 MaterialRef 转换为 MaterialData 传给 AgentPool
                    val materialData = _board.value.materials
                        .filter { it.mimeType.startsWith("image/") && it.base64.isNotBlank() }
                        .map { MaterialData(mimeType = it.mimeType, base64 = it.base64, fileName = it.fileName) }
                    agentPool.callAgentStreamingByRoleId(
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
                    ).collect { delta ->
                        sb.append(delta)
                        val accumulated = sb.toString()
                        _speeches.value = _speeches.value.map {
                            if (it.id == recordId) it.copy(content = accumulated, isStreaming = true)
                            else it
                        }
                    }

                    // 流结束，解析完整响应
                    val raw = sb.toString()
                    log("[${picked.role}] raw=${raw.take(300)}")
                    val response = UnifiedResponse.parse(raw, picked.canVote)
                    log("[${picked.role}] speak=${response.wantsToSpeak} vote=${response.voteBull}")

                    if (response.wantsToSpeak && response.content.isNotBlank()) {
                        anySpoke = true

                        // 更新为干净的 content（去掉 SPEAK/VOTE/TAGS 元数据）
                        _speeches.value = _speeches.value.map {
                            if (it.id == recordId) it.copy(content = response.content, isStreaming = false)
                            else it
                        }

                        // 写入 Board
                        val tags = response.normalizedTags.map { it.name }
                        addBoardMessage(picked.role, response.content, tags)

                        // 投票（Map 去重）
                        if (response.voteBull != null) {
                            _board.value = _board.value.copy(
                                votes = _board.value.votes + (picked.role to BoardVote(
                                    role = picked.role,
                                    agree = response.voteBull,
                                    round = _board.value.round,
                                ))
                            )
                            log("[Vote] ${picked.role} = ${if (response.voteBull) "Agree" else "Disagree"}")
                        }

                        // 共识
                        val b = _board.value
                        if (b.hasConsensus() && !b.consensus) {
                            _board.value = b.copy(consensus = true)
                            log("[Consensus] Bull ${(b.bullRatio() * 100).toInt()}%")
                        }

                        updatePhase()

                        // 持久化
                        val finalRecord = _speeches.value.last { it.id == recordId }
                        persistSpeech(finalRecord)
                    } else {
                        // SPEAK=NO：移除占位记录，不消耗轮次
                        _speeches.value = _speeches.value.filter { it.id != recordId }
                        log("[${picked.role}] SPEAK=NO（不消耗轮次）")
                    }
                } catch (e: Exception) {
                    log("[${picked.role}] 错误: ${e.message}")
                }
            }

            // ── ⑥ 熔断检测 ──────────────────────────────────
            if (anySpoke) {
                consecutiveSilentRounds = 0
                advanceRound()
            } else {
                consecutiveSilentRounds++
                if (consecutiveSilentRounds >= CIRCUIT_BREAKER_THRESHOLD) {
                    log("[CircuitBreak] $CIRCUIT_BREAKER_THRESHOLD consecutive silent rounds (possible network issue), terminating early")
                    // 将错误信息展示给用户
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

    // ── Board 操作（不可变）───────────────────────────────────

    private fun addBoardMessage(role: String, content: String, rawTags: List<String>) {
        val b = _board.value
        val newMsg = BoardMessage(
            role = role, content = content,
            round = b.round, rawTags = rawTags,
        )
        _board.value = b.copy(messages = b.messages + newMsg)
        log("[Board] +$role tags=$rawTags: ${content.take(60)}...")
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

    private suspend fun doRating(): String? {
        val prompt = supervisor.buildRatingPrompt(_board.value)
        val content = systemLlm.quickCall(prompt)
        return parseRating(content)
    }

    private fun defaultRating(): String = preset.ratingScale.getOrElse(preset.ratingScale.size / 2) { "Hold" }

    private fun finishWithRating(rating: String) {
        _board.value = _board.value.copy(
            finished = true,
            finalRating = rating,
            phase = BoardPhase.RATING,
        )
        log("[Rating] Final rating: $rating")
        if (agents.any { it.role == "executor" }) {
            _board.value = _board.value.copy(phase = BoardPhase.EXECUTION)
        }
        updateSessionFinished()
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
        activationK = newPreset.mandateInt("activation_k", 2)
        summaryInterval = newPreset.mandateInt("summary_interval", 2)

        val supervisorRoleIds = setOf("supervisor", "coordinator", "judge", "area_chair")
        val supervisorPresetRole = newPreset.roles.find { it.id in supervisorRoleIds }
            ?: newPreset.roles.last()

        supervisor = GenericSupervisor(
            presetRole = supervisorPresetRole,
            ratingScale = newPreset.ratingScale,
            committeeLabel = newPreset.committeeLabel,
        )

        agents = newPreset.roles
            .filter { it.id != supervisorPresetRole.id }
            .map { role ->
                GenericAgent(
                    presetRole = role,
                    systemPrompt = "",
                    canUseTools = role.canUseTools,
                )
            }

        evolverRegistry = buildEvolverRegistry()
        toolAgentRoleId = findToolAgentRoleId()
        Log.i(TAG, "Reconfigured to preset: ${newPreset.id} with ${agents.size} agents")
    }
}
