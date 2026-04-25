package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.ErrorType
import com.znliang.committee.engine.MaterialData
import com.znliang.committee.engine.StreamResult
import kotlinx.coroutines.flow.Flow

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  MeetingOrchestrator — 会议核心循环与决策逻辑
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  从 AgentRuntime 中分离的协作者，负责：
 *    - runLoop() 主循环
 *    - Agent 选择/评分
 *    - 流式收集 (collectWithBatchedEmission)
 *    - 摘要更新 / Supervisor 判断 / 分歧检测
 *    - 评级、投票统计、置信度计算
 *    - 情报官预搜索
 */
class MeetingOrchestrator(private val ctx: RuntimeContext) {

    // ── 流式收集 ──────────────────────────────────────────────

    /** 流式收集结果 — 返回遇到的第一个错误（如有） */
    sealed interface CollectOutcome {
        data object Success : CollectOutcome
        data class StreamError(val type: ErrorType, val message: String) : CollectOutcome
    }

    /**
     * Batched emission for streaming tokens.
     * Collects Flow<StreamResult> into [sb], emits UI updates at most every [BATCH_MS] ms (~12fps).
     * Reduces StateFlow emissions from ~2000/response to ~25.
     *
     * @return CollectOutcome indicating success or the first error encountered.
     */
    suspend fun collectWithBatchedEmission(
        flow: Flow<StreamResult>,
        sb: StringBuilder,
        recordId: String,
        extractContent: (String) -> String,
    ): CollectOutcome {
        val BATCH_MS = 80L
        var lastEmitTime = 0L
        var error: CollectOutcome = CollectOutcome.Success
        flow.collect { result ->
            when (result) {
                is StreamResult.Token -> {
                    sb.append(result.text)
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= BATCH_MS) {
                        lastEmitTime = now
                        ctx.emitSpeechUpdate(recordId, extractContent(sb.toString()))
                    }
                }
                is StreamResult.Error -> {
                    if (error is CollectOutcome.Success) {
                        error = CollectOutcome.StreamError(result.type, result.message)
                    }
                }
                is StreamResult.Done -> { /* no-op, flow will complete */ }
            }
        }
        // Final flush — ensure last tokens are visible
        ctx.emitSpeechUpdate(recordId, extractContent(sb.toString()))
        return error
    }

    // ── 流式内容提取 ──────────────────────────────────────────

    /**
     * 从流式累积文本中提取 CONTENT 之后、VOTE/TAGS 之前的干净内容。
     * 流式过程中用户只看到 CONTENT 部分，避免暴露 SPEAK/REASONING 等元数据。
     */
    fun extractStreamingContent(accumulated: String): String {
        // 寻找 CONTENT: 或 CONTENT：标记
        val contentIdx = accumulated.indexOfContentMarker()
        if (contentIdx < 0) {
            // 兜底：如果已累积 >100 字符仍无 CONTENT 标记，说明 LLM 未遵循格式
            // 直接展示原始文本（去掉可能存在的 SPEAK/REASONING 前缀）
            if (accumulated.length > 100) {
                return accumulated.lines()
                    .filter { line ->
                        val t = line.trim().uppercase()
                        !t.startsWith("SPEAK") && !t.startsWith("REASONING")
                    }
                    .joinToString("\n").trim()
                    .ifBlank { accumulated.trim() }
            }
            return "" // CONTENT 尚未出现 → "思考中"
        }

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

    // ── 情报官预搜索 ──────────────────────────────────────────

    /**
     * 情报官预搜索（参考 Hermes 的 proactive info gathering）
     *
     * 在会议正式开始前，根据历史信息缺口记忆自动搜索补充信息：
     * 1. 从 tool-capable agent 的 evolver 加载历史缺口
     * 2. 基于标的 + 缺口构造搜索查询
     * 3. 通过 WebSearchService 执行搜索
     * 4. 搜索结果注入 blackboard 的 preGatheredInfo
     * 5. 情报官发言时会自动看到这些预收集的信息
     */
    suspend fun preMeetingIntelSearch(subject: String) {
        try {
            val toolEvolver = ctx.toolAgentRoleId?.let { ctx.evolverRegistry[it] }
            val gapMemory = toolEvolver?.getPreMeetingMemory() ?: emptyList()
            if (gapMemory.isEmpty()) {
                ctx.log("[PreSearch] No historical info gaps, skipping")
                return
            }

            ctx.updateBoard { it.copy(preSearchStatus = PreSearchStatus.SEARCHING) }

            // 获取当前日期，确保搜索最新信息
            val today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日", java.util.Locale.CHINA))
            ctx.log("[PreSearch] Found ${gapMemory.size} historical info gaps, date=$today, starting pre-search...")

            // 构造搜索查询：标的 + 日期 + 缺口
            val topGaps = gapMemory.take(3)
            val searchResults = mutableListOf<String>()

            for (gap in topGaps) {
                val query = "$subject $today $gap"
                ctx.log("[PreSearch] Query: $query")
                try {
                    val results = ctx.toolRegistry.executeBuiltinTool("web_search",
                        """{"query":"$query"}"""
                    )
                    if (!results.startsWith("[error]") && !results.startsWith("[无")) {
                        searchResults.add("### 查询: $query\n$results")
                        ctx.log("[PreSearch] Success, result length=${results.length}")
                    } else {
                        ctx.log("[PreSearch] Query '$query' no valid results")
                    }
                } catch (e: Exception) {
                    ctx.log("[PreSearch] Query '$query' failed: ${e.message}")
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
                ctx.updateBoard { it.copy(preGatheredInfo = info, preSearchStatus = PreSearchStatus.DONE) }
                ctx.log("[PreSearch] Injected ${searchResults.size} pre-search results into blackboard")
            } else {
                ctx.updateBoard { it.copy(preSearchStatus = PreSearchStatus.DONE) }
            }
        } catch (e: Exception) {
            ctx.log("[PreSearch] Failed: ${e.message}")
            ctx.updateBoard { it.copy(preSearchStatus = PreSearchStatus.FAILED) }
        }
    }

    // ── Summary Memory ───────────────────────────────────────

    suspend fun updateSummary() {
        val board = ctx.boardValue
        if (board.messages.size < 3) return
        ctx.log("[Summary] 生成讨论摘要（${board.messages.size}条消息）...")
        val prompt = ctx.supervisor.buildSummaryPrompt(board)
        val newSummary = ctx.systemLlm.quickCall(prompt)
        if (newSummary.isNotBlank()) {
            ctx.updateBoard { it.copy(
                summary = newSummary,
                lastSummaryRound = board.round,
            ) }
            ctx.log("[Summary] 已更新（${newSummary.length}字）")
        }
    }

    // ── Supervisor 降频 ──────────────────────────────────────

    fun shouldSupervisorComment(board: Blackboard): Boolean {
        if (board.round <= 1) return false
        if (board.messages.any { it.role == "supervisor" && it.round == board.round }) return false
        if (board.consensus) return false
        return board.messages.count { it.role != "supervisor" } >= 3
    }

    suspend fun askSupervisorFinish(board: Blackboard): Boolean {
        val prompt = ctx.supervisor.buildFinishPrompt(board)
        val response = ctx.systemLlm.quickCall(prompt)
        val r = response.trim().uppercase()
        return r.startsWith("YES") || r.startsWith("Y")
    }

    // ── 分歧检测 ──────────────────────────────────────────────

    /**
     * 分歧检测 — 识别最近两条发言是否存在立场对立
     * 如果检测到对立观点，插入一条系统事件标注分歧焦点
     */
    fun detectAndMarkDivergence(currentRole: String, currentTags: List<MsgTag>) {
        val board = ctx.boardValue
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
                ctx.addSpeech(SpeechRecord(
                    id = "div_${System.currentTimeMillis()}",
                    agent = "system",
                    content = divergenceNote,
                    summary = "",
                    round = board.round,
                ))
                ctx.log("[Divergence] $divergenceNote")
                return // 每条发言最多标注一次
            }
        }
    }

    // ── 评级 / 投票 / 默认 ──────────────────────────────────────

    suspend fun doRating(): String? {
        val prompt = ctx.supervisor.buildRatingPrompt(ctx.boardValue)
        val content = ctx.systemLlm.quickCall(prompt)
        return parseRating(content)
    }

    /** Vote-majority rating: pick the rating from ratingScale based on vote results */
    fun doVoteMajorityRating(): String? {
        val board = ctx.boardValue
        return when (ctx.voteType) {
            VoteType.BINARY -> {
                if (board.agreeRatio() >= 0.5f) ctx.preset.ratingScale.firstOrNull()
                else ctx.preset.ratingScale.lastOrNull()
            }
            VoteType.SCALE -> {
                val avg = board.averageScore()
                // Map 1-10 score to ratingScale index
                val idx = ((1f - (avg - 1f) / 9f) * (ctx.preset.ratingScale.size - 1)).toInt()
                    .coerceIn(0, ctx.preset.ratingScale.size - 1)
                ctx.preset.ratingScale[idx]
            }
            VoteType.MULTI_STANCE -> {
                board.majorityStance() ?: ctx.preset.ratingScale.firstOrNull()
            }
        }
    }

    fun defaultRating(): String = ctx.preset.ratingScale.getOrElse(ctx.preset.ratingScale.size / 2) { "Hold" }

    /** 因错误终止会议 — 不产生评级/结论，仅报错 */
    fun abortWithError(errorMessage: String) {
        ctx.updateBoard { it.copy(
            finished = true,
            phase = BoardPhase.DONE,
            errorMessage = errorMessage,
        ) }
        ctx.log("[Abort] $errorMessage")
        ctx.updateSessionFinished()
    }

    fun finishWithRating(rating: String) {
        // 计算决策置信度
        val (confidence, breakdown) = calculateConfidence()

        ctx.updateBoard { it.copy(
            finished = true,
            finalRating = rating,
            phase = BoardPhase.RATING,
            decisionConfidence = confidence,
            confidenceBreakdown = breakdown,
        ) }
        ctx.log("[Rating] Final rating: $rating, confidence: $confidence%")
        if (ctx.agents.any { it.role == "executor" }) {
            ctx.updateBoard { it.copy(phase = BoardPhase.EXECUTION) }
        }
        ctx.updateSessionFinished()
        // 异步评估各 Agent 贡献度
        ctx.launchInScope { scoreContributions() }
    }

    fun updatePhase() {
        val b = ctx.boardValue
        val newPhase = b.inferPhase()
        if (b.phase != newPhase) {
            ctx.updateBoard { it.copy(phase = newPhase) }
            ctx.log("[Phase] ${b.phase} → $newPhase")
            // P0-3: 阶段转换事件气泡
            ctx.addSpeech(SpeechRecord(
                id = "phase_${System.currentTimeMillis()}",
                agent = "system",
                content = "── ${b.phase.name} → ${newPhase.name} ──",
                summary = "",
                round = b.round,
                isPhaseTransition = true,
            ))
        }
    }

    fun advanceRound() {
        if (!ctx.boardValue.finished) {
            ctx.updateBoard { it.copy(round = it.round + 1) }
        }
    }

    fun parseRating(content: String): String? {
        // Sort by length descending so "Ship with fixes" matches before "Ship"
        val scalePattern = ctx.preset.ratingScale.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        // 覆盖所有 outputType 的关键字变体（rating/decision/score/open）
        val keywords = listOf(
            "Final Rating", "Final Decision", "Final Score", "Final Verdict",
            "Decision", "Rating", "Score", "Verdict", "Conclusion", "Result",
            "最终评定", "最终评级", "最终决定", "最终结论", "综合评分",
            "结论", "评定", "评级", "决定", "评分", "裁决",
        ).joinToString("|") { Regex.escape(it) }
        val regex = Regex("""(?:$keywords)[：:\s]*($scalePattern)""", RegexOption.IGNORE_CASE)
        val match = regex.find(content)
        if (match != null) return match.groupValues.getOrNull(1)
        // 兜底：直接在全文中搜索 ratingScale 的精确值
        for (rating in ctx.preset.ratingScale) {
            if (content.contains(rating, ignoreCase = true)) return rating
        }
        return null
    }

    // ── 贡献度评估 ──────────────────────────────────────────────

    /**
     * 让 Supervisor 评估各 Agent 的发言贡献度
     */
    private suspend fun scoreContributions() {
        try {
            val board = ctx.boardValue
            val agentRoles = ctx.agents.map { it.role }.filter { roleId ->
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

            val result = ctx.systemLlm.quickCall(prompt)
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
                ctx.updateBoard { it.copy(contributionScores = scores) }
                ctx.log("[ContribScore] Scored ${scores.size} agents")
            }
        } catch (e: Exception) {
            ctx.log("[ContribScore] Error: ${e.message}")
        }
    }

    // ── 置信度计算 ──────────────────────────────────────────────

    /**
     * 计算决策置信度（0-100）
     *
     * 基于三个维度：
     * 1. 共识度（40分）— 投票一致性
     * 2. 讨论充分度（35分）— 参与者数量、轮次、发言量
     * 3. 分歧解决度（25分）— 是否有未回应的质疑
     */
    fun calculateConfidence(): Pair<Int, String> {
        val board = ctx.boardValue
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
        val totalAgents = ctx.agents.size
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

    // ── 核心循环 ─────────────────────────────────────────────────

    suspend fun runLoop() {
        var consecutiveSilentRounds = 0  // 熔断器：连续静默轮次（仅计网络/解析错误导致的静默）
        val CIRCUIT_BREAKER_THRESHOLD = 3 // 连续3轮无人发言即熔断
        var consecutiveChosenSilent = 0  // SPEAK:NO 导致的连续静默（区别于网络错误）

        while (!ctx.boardValue.finished) {
            // ── Human-in-the-Loop: 暂停检查（zero-cost suspend） ──
            if (ctx.isPausedValue) {
                ctx.awaitResumeOrFinish()
            }

            val board = ctx.boardValue

            if (board.round > ctx.maxRounds) {
                ctx.log("[SafetyValve] Exceeded ${ctx.maxRounds} rounds")
                finishWithRating(defaultRating())
                break
            }

            ctx.log("[Round ${board.round}] ────────────────────────────")

            // ── ① Summary Memory ──────────────────────────────────────
            if (board.round > 1 && board.round - board.lastSummaryRound >= ctx.summaryInterval) {
                updateSummary()
            }

            // ── ② Supervisor 结束判断（降频 + 动态门槛 + mandate 驱动） ─────
            // debate_rounds: 前 N 轮禁止结束判断
            val nonSupervisorMsgs = board.messages.count { it.role != "supervisor" }
            val minMsgThreshold = ctx.agents.size * 3  // 每个 agent 平均至少发言 3 次
            val minRoundThreshold = maxOf(ctx.debateRounds, ctx.agents.size, 5) // 至少 5 轮或 agent 数

            // ── 硬性保障：必须有足够多不同参与者发言过，不能依赖 LLM 判断 ──
            val distinctSpeakers = board.messages
                .map { it.role }
                .filter { it != "supervisor" && it != "human" }
                .distinct().size
            val minDistinctSpeakers = maxOf(ctx.agents.size - 1, 2) // 至少 N-1 人或 2 人
            val hasEnoughSpeakers = distinctSpeakers >= minDistinctSpeakers

            if (ctx.hasSupervisor &&
                hasEnoughSpeakers &&
                board.round > minRoundThreshold &&
                nonSupervisorMsgs >= minMsgThreshold &&
                (board.round % 4 == 0 || board.consensus || board.round >= minRoundThreshold * 2)) {
                val shouldFinish = askSupervisorFinish(board)
                ctx.log("[Supervisor] shouldFinish=$shouldFinish (msgs=$nonSupervisorMsgs, minMsgs=$minMsgThreshold, minRounds=$minRoundThreshold, speakers=$distinctSpeakers/$minDistinctSpeakers)")
                if (shouldFinish) {
                    // consensus_required: override supervisor YES if no consensus
                    if (ctx.consensusRequired && !board.hasConsensus(ctx.voteType) && board.round < ctx.maxRounds - 1) {
                        ctx.log("[Consensus] Required but not reached, continuing discussion")
                    } else {
                        val rating = if (ctx.supervisorFinalCall) doRating() else doVoteMajorityRating()
                        finishWithRating(rating ?: defaultRating())
                        break
                    }
                }
            } else if (!ctx.hasSupervisor && hasEnoughSpeakers &&
                board.round > minRoundThreshold &&
                nonSupervisorMsgs >= minMsgThreshold) {
                // No supervisor: use vote majority to decide when to finish
                if (board.hasConsensus(ctx.voteType) || board.round >= minRoundThreshold * 2) {
                    ctx.log("[NoSupervisor] Finishing by vote majority")
                    val rating = doVoteMajorityRating()
                    finishWithRating(rating ?: defaultRating())
                    break
                }
            }

            // ── ③ Supervisor 点评（降频，仅当 has_supervisor=true） ──────
            if (ctx.hasSupervisor && shouldSupervisorComment(board)) {
                val comment = ctx.systemLlm.quickCall(ctx.supervisor.buildSupervisionPrompt(board))
                if (comment.isNotBlank() && comment.length > 10) {
                    ctx.addBoardMessage(ctx.supervisor.role, comment, emptyList())
                }
            }

            // ── ④ 加权选择 Agent ──────────────────────────────────
            val eligible = ctx.agents.filter { it.eligible(ctx.boardValue) }
            ctx.log("[eligible] ${eligible.map { it.role }}")

            if (eligible.isEmpty()) {
                ctx.log("[eligible] 无人有资格")
                advanceRound()
                continue
            }

            val scored = eligible.map { agent ->
                    val baseScore = agent.scoring(ctx.boardValue)
                    val userWeight = ctx.boardValue.userWeights[agent.role] ?: 1.0f

                    // ── 未发言 Agent 优先 bonus ──
                    val hasSpokeEver = ctx.boardValue.messages.any { it.role == agent.role }
                    val neverSpokeBonus = if (!hasSpokeEver) 5.0 else 0.0

                    // ── SPEAK:NO 连续惩罚处理 ──
                    // 统计连续 SPEAK:NO 次数（通过"被选中但没写入 board"来推断）
                    val totalTimesSelected = ctx.boardValue.round  // 粗略：可能被选中的最大次数
                    val timesSpoken = ctx.boardValue.messages.count { it.role == agent.role }
                    val silentRatio = if (totalTimesSelected > 0) 1.0 - (timesSpoken.toDouble() / totalTimesSelected) else 0.0
                    val silentBonus = if (hasSpokeEver && silentRatio > 0.5 && ctx.boardValue.round > 2) 3.0 else 0.0

                    agent to (baseScore + neverSpokeBonus + silentBonus) * userWeight
                }
                .sortedByDescending { it.second }
            scored.forEach { (agent, score) ->
                ctx.log("[score] ${agent.role} = ${"%.1f".format(score)}")
            }

            // ── ⑤ 取 top-K 并行发言（前3轮放大K值，SPEAK=NO 不消耗轮次） ────
            val effectiveK = if (board.round <= 3) {
                minOf(ctx.agents.size, ctx.activationK + 1)
            } else {
                ctx.activationK
            }

            // ── 严格交替模式（辩论赛）：强制按轮次交替选择，忽略 scoring ──
            val topK = if (ctx.strictAlternation && ctx.agents.size == 2 && board.round > 3) {
                val lastSpeaker = board.messages.lastOrNull {
                    it.role != "supervisor" && it.role != "human"
                }?.role
                val nextAgent = eligible.firstOrNull { it.role != lastSpeaker }
                    ?: eligible.first()
                ctx.log("[StrictAlternation] last=$lastSpeaker → next=${nextAgent.role}")
                listOf(nextAgent to 0.0)
            } else {
                scored.take(effectiveK)
            }
            var anySpoke = false
            var anyChosenSilent = false  // 是否有 agent 收到响应但选择 SPEAK:NO

            for ((idx, pair) in topK.withIndex()) {
                val (picked, _) = pair
                if (ctx.boardValue.finished) break

                // ── 限速防护：Agent 之间插入间隔，避免突发请求触发 429 ──
                if (idx > 0) {
                    kotlinx.coroutines.delay(200)
                }

                try {
                    var prompt = picked.buildUnifiedPrompt(ctx.boardValue)

                    // ── 确保所有参与者都有发言机会：如果还没够多人发言过，强制发言 ──
                    val currentDistinctSpeakers = ctx.boardValue.messages
                        .map { it.role }
                        .filter { it != "supervisor" && it != "human" }
                        .distinct().size
                    val hasNeverSpoken = !ctx.boardValue.messages.any { it.role == picked.role }
                    val needMoreSpeakers = currentDistinctSpeakers < ctx.agents.size
                    if (hasNeverSpoken && needMoreSpeakers) {
                        prompt += "\n\n🔴 重要：你还从未在本次讨论中发言！讨论需要所有参与者的声音。你必须发言（SPEAK: YES），这是强制要求。"
                    }

                    // ── 长期沉默提示：如果 agent 已有 2+ 轮没发言，注入提醒 ──
                    val lastSpokeRound = ctx.boardValue.messages.lastOrNull { it.role == picked.role }?.round ?: 0
                    if (lastSpokeRound > 0 && ctx.boardValue.round - lastSpokeRound >= 2) {
                        prompt += "\n\n⚠️ 你已经${ctx.boardValue.round - lastSpokeRound}轮没有发言了，请重新审视讨论进展，积极参与讨论。SPEAK: YES 是强烈建议。"
                    }

                    // ── ⑤½ 经验注入：回忆相关经验 + 技能 + 专属Evolver记忆 ──
                    if (picked is EvolvableAgent) {
                        try {
                            val experiences = picked.recallRelevantExperience(
                                ctx.boardValue, ctx.evolutionRepo.evolutionDao()
                            )
                            val skills = ctx.skillLibrary.findRelevantSkills(
                                picked.role, ctx.boardValue.subject,
                                ctx.boardValue.summary
                            )

                            // 统一使用各Agent的专属Evolver注入记忆
                            val evolver = ctx.evolverRegistry[picked.role]
                            var enrichedPrompt = prompt

                            if (experiences.isNotEmpty() || skills.isNotEmpty()) {
                                enrichedPrompt = picked.enrichPrompt(prompt, experiences, skills)
                                ctx.log("[进化] ${picked.role} 注入 ${experiences.size}条经验 + ${skills.size}个技能")
                            }

                            // 额外注入：各Agent专属的Evolver预会议记忆
                            if (evolver != null) {
                                val preMemory = evolver.getPreMeetingMemory()
                                if (preMemory.isNotEmpty()) {
                                    enrichedPrompt = enrichedPrompt + "\n\n## 🧠 专属进化记忆（从过去会议中学到的教训）\n" +
                                        preMemory.joinToString("\n") { "- $it" } +
                                        "\n请参考以上记忆来指导你的发言。"
                                    ctx.log("[进化] ${picked.role} 注入 ${preMemory.size}条专属记忆")
                                }
                            }

                            prompt = enrichedPrompt
                        } catch (e: Exception) {
                            ctx.log("[进化] ${picked.role} 经验注入失败: ${e.message}")
                        }
                    }

                    if (prompt.isBlank()) {
                        ctx.log("[${picked.role}] prompt 为空")
                        continue
                    }

                    val roleId = picked.role
                    val round = ctx.boardValue.round
                    val recordId = "sp_${System.currentTimeMillis()}"

                    // 创建流式占位记录
                    ctx.addSpeech(SpeechRecord(
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
                    val materialData = ctx.boardValue.materials
                        .filter { it.mimeType.startsWith("image/") && it.base64.isNotBlank() }
                        .map { MaterialData(mimeType = it.mimeType, base64 = it.base64, fileName = it.fileName) }
                    val collectOutcome = collectWithBatchedEmission(
                        flow = ctx.agentPool.callAgentStreamingByRoleId(
                            roleId,
                            MicContext(
                                traceId = "ag_${System.currentTimeMillis()}",
                                causedBy = "system",
                                round = round,
                                phase = MeetingState.PHASE1_DEBATE,
                                subject = ctx.boardValue.subject,
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
                    ctx.log("[${picked.role}] raw=${raw.take(300)}")

                    // ── 错误检测：类型化处理 ──
                    if (collectOutcome is CollectOutcome.StreamError) {
                        ctx.removeSpeech(recordId)
                        if (collectOutcome.type == ErrorType.BILLING) {
                            // 计费不足 — 终止会议，通知用户
                            ctx.log("[${picked.role}] BILLING ERROR — terminating meeting")
                            abortWithError("API 余额不足，请充值后重试。\n${collectOutcome.message}")
                            return // exit runLoop entirely
                        } else if (collectOutcome.type == ErrorType.RATE_LIMIT) {
                            // 限速 — 主动退避后继续（不立即尝试下一个 agent）
                            ctx.log("[${picked.role}] RATE_LIMIT — backing off 2s before next agent")
                            kotlinx.coroutines.delay(2000)
                            continue
                        } else {
                            // 网络/API 错误 — 视为网络静默，触发熔断器
                            ctx.log("[${picked.role}] API ERROR (treated as network failure): ${collectOutcome.message}")
                            continue
                        }
                    }

                    val response = UnifiedResponse.parse(raw, picked.canVote, ctx.voteType)
                    ctx.log("[${picked.role}] speak=${response.wantsToSpeak} vote=${response.voteBull}")

                    // ── SPEAK:NO 安全阀：如果 agent 从未发言过且有内容，强制视为发言 ──
                    val neverSpokenBefore = !ctx.boardValue.messages.any { it.role == picked.role }
                    val effectiveWantsToSpeak = response.wantsToSpeak ||
                        (neverSpokenBefore && response.content.isNotBlank())
                    if (!response.wantsToSpeak && effectiveWantsToSpeak) {
                        ctx.log("[${picked.role}] SPEAK:NO overridden — agent has never spoken, forcing speech")
                    }

                    if (effectiveWantsToSpeak && response.content.isNotBlank()) {
                        anySpoke = true

                        // 更新为干净的 content（去掉 SPEAK/VOTE/TAGS 元数据）
                        ctx.updateSpeechInBacking(recordId) { record ->
                            record.copy(
                                content = response.content,
                                reasoning = response.reasoning,
                                isStreaming = false,
                            )
                        }

                        // 写入 Board
                        val tags = response.normalizedTags.map { it.name }
                        ctx.addBoardMessage(picked.role, response.content, tags)

                        // ── 分歧检测：标注关键对立点 ──
                        detectAndMarkDivergence(picked.role, response.normalizedTags)

                        // 投票（Map 去重，携带扩展投票数据）
                        if (response.voteBull != null) {
                            ctx.updateBoard { b -> b.copy(
                                votes = b.votes + (picked.role to BoardVote(
                                    role = picked.role,
                                    agree = response.voteBull,
                                    reason = response.content.take(200),
                                    round = b.round,
                                    numericScore = response.numericScore,
                                    stanceLabel = response.stanceLabel,
                                ))
                            ) }
                            val voteLabel = when (ctx.voteType) {
                                VoteType.SCALE -> "Score=${response.numericScore}"
                                VoteType.MULTI_STANCE -> "Stance=${response.stanceLabel}"
                                VoteType.BINARY -> if (response.voteBull) "Agree" else "Disagree"
                            }
                            ctx.log("[Vote] ${picked.role} = $voteLabel")
                            // P0-1: 将投票标签写回 SpeechRecord，UI 可内联展示
                            ctx.updateSpeechInBacking(recordId) { record ->
                                record.copy(voteLabel = voteLabel)
                            }
                        }

                        // 共识
                        val b = ctx.boardValue
                        if (b.hasConsensus(ctx.voteType) && !b.consensus) {
                            ctx.updateBoard { it.copy(consensus = true) }
                            val pct = (b.agreeRatio() * 100).toInt()
                            ctx.log("[Consensus] Agree $pct%")
                            // P0-2: 共识达成事件气泡
                            ctx.addSpeech(SpeechRecord(
                                id = "consensus_${System.currentTimeMillis()}",
                                agent = "system",
                                content = "✅ Consensus reached — $pct% agreement",
                                summary = "",
                                round = b.round,
                                isConsensusEvent = true,
                            ))
                        }

                        updatePhase()

                        // 持久化
                        val finalRecord = ctx.speechesValue.last { it.id == recordId }
                        ctx.persistSpeech(finalRecord)
                    } else {
                        // SPEAK=NO：移除占位记录，不消耗轮次
                        anyChosenSilent = true
                        ctx.removeSpeech(recordId)
                        ctx.log("[${picked.role}] SPEAK=NO（不消耗轮次）")
                    }
                } catch (e: Exception) {
                    ctx.log("[${picked.role}] 错误: ${e.message}")
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
                    ctx.log("[CircuitBreak] $consecutiveChosenSilent rounds of SPEAK:NO, forcing next round")
                    consecutiveChosenSilent = 0
                }
                advanceRound()
            } else {
                // 真正的静默：没有 agent 响应（网络/解析错误）
                consecutiveSilentRounds++
                if (consecutiveSilentRounds >= CIRCUIT_BREAKER_THRESHOLD) {
                    ctx.log("[CircuitBreak] $CIRCUIT_BREAKER_THRESHOLD consecutive silent rounds (possible network issue), terminating early")
                    abortWithError("连续 $CIRCUIT_BREAKER_THRESHOLD 轮无法获取 AI 响应，请检查网络连接和 API 配置后重试。")
                    break
                }
                ctx.log("[CircuitBreak] Consecutive silent rounds $consecutiveSilentRounds/$CIRCUIT_BREAKER_THRESHOLD")
                advanceRound()
            }
        }
    }
}
