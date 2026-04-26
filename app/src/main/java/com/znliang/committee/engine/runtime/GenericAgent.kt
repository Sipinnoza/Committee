package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.PresetRole

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  GenericAgent -- 通用可配置 Agent（由 PresetRole 驱动）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  替代 5 个硬编码 Agent（AnalystAgent / RiskAgent / StrategistAgent
 *  / IntelAgent / ExecutorAgent），通过 PresetRole 配置任意角色的 Agent。
 *
 *  实现 EvolvableAgent 接口，天然支持：
 *   - 经验回忆 / recallRelevantExperience
 *   - Prompt 注入 / enrichPrompt
 *
 *  @param promptStyle 决定 CONTENT 输出结构：debate/review/collaborative/creative/pitch
 *  @param voteType    决定 VOTE 指令格式：BINARY/SCALE/MULTI_STANCE
 *  @param voteOptions MULTI_STANCE 模式的选项列表（如 ["Invest","Conditional Interest","Pass"]）
 */
class GenericAgent(
    private val presetRole: PresetRole,
    val canUseTools: Boolean = false,
    private val scoringBonus: Double = 0.0,
    private val promptStyle: String = "debate",
    private val voteType: VoteType = VoteType.BINARY,
    private val voteOptions: List<String> = emptyList(),
) : EvolvableAgent {

    override val role: String = presetRole.id
    override val displayName: String = presetRole.displayName

    /**
     * 从 PresetRole.stance 自动推断 attentionTags，
     * 避免全部落入 GENERAL 导致 scoring tag 匹配失效 + 互引机制失效。
     */
    override val attentionTags: List<MsgTag> = inferAttentionTags(presetRole.stance)

    override val canVote: Boolean = true

    // ── eligible ──────────────────────────────────────────────

    /**
     * 标准 "spoken < 2 this round" 规则。
     * canUseTools 的 Agent 允许最多 5 次（情报类角色需要多次补充）。
     */
    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        val limit = if (canUseTools) 5 else 2
        return spokenThisRound < limit
    }

    // ── scoring ───────────────────────────────────────────────

    /**
     * 通用评分，适用于 ANY 角色。
     *
     * 评分维度：
     *   1. 距上次发言的轮数（capped at 5） * 1.0
     *   2. 近期消息与 attentionTags 匹配度 * 2.0
     *   3. 投票分歧时 → 应用 scoringBonus
     *   4. 本轮已发言 → -3.0
     *   5. 第一轮 + canUseTools → +3.0 优先发言
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0

        // ① 距上次发言的轮数
        val lastSpokeRound = board.messages.lastOrNull { it.role == role }?.round ?: 0
        val roundsSinceLastSpoke = board.round - lastSpokeRound
        score += minOf(roundsSinceLastSpoke, 5) * 1.0

        // ② 近期消息与 attentionTags 的匹配度
        val recentMessages = board.messages.takeLast(4)
        val relevantCount = recentMessages.count { msg ->
            msg.normalizedTags.any { it in attentionTags }
        }
        score += relevantCount * 2.0

        // ③ 投票分歧 → 应用 scoringBonus
        val agreeCount = board.votes.values.count { it.agree }
        val disagreeCount = board.votes.size - agreeCount
        val hasDivergence = board.votes.size >= 2 &&
            kotlin.math.abs(agreeCount - disagreeCount) <= 1
        if (hasDivergence) {
            score += scoringBonus
        }

        // ④ 本轮已发言 → 降分
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        score -= spokenThisRound * 3.0

        // ⑤ 第一轮 + 工具型 Agent → 优先发言
        if (board.round == 1 && canUseTools) {
            score += 3.0
        }

        return score
    }

    // ── buildUnifiedPrompt ────────────────────────────────────

    /**
     * 从 PresetRole + promptStyle + voteType 动态构建 prompt。
     *
     * 根据 promptStyle 生成不同的 CONTENT 结构和 SPEAK/VOTE 规则，
     * 以匹配 debate / review / collaborative / creative / pitch 五种会议模式。
     */
    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)

        // 动态指令：根据讨论阶段生成
        val myMessages = board.messages.filter { it.role == role }
        val isFirstSpeech = myMessages.isEmpty()
        val hasOtherSpeakers = board.messages.any { it.role != role && it.role != "supervisor" }

        // ── 防重复：提取自身已有发言摘要 ──
        val myPriorSummary = buildPriorSummary(myMessages)

        // 前 3 轮强制发言
        val isEarlyRound = board.round <= 3
        // collaborative / creative 模式始终强制发言
        val alwaysSpeak = promptStyle in setOf("collaborative", "creative")

        // ── 互引机制（仅 debate / review / pitch 使用） ──
        val opposingQuotes = if (promptStyle in setOf("debate", "review", "pitch")) {
            buildOpposingQuotes(board)
        } else {
            // collaborative / creative 模式取近期所有发言做参考
            buildRecentContext(board)
        }

        val instruction = buildInstruction(
            board, isFirstSpeech, hasOtherSpeakers, isEarlyRound, opposingQuotes
        )

        val speakRule = buildSpeakRule(isEarlyRound, alwaysSpeak)
        val contentFormat = buildContentFormat()
        val voteRule = buildVoteRule()
        val evolutionGuide = buildEvolutionGuide()

        return """你是【${presetRole.displayName}｜${presetRole.stance}视角｜超级智能体】。讨论主题：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction
$myPriorSummary

━━━━━━━━━━━━━━━━━━━━━━━━
角色职责
━━━━━━━━━━━━━━━━━━━━━━━━
${presetRole.responsibility}

$evolutionGuide

输出格式（严格遵守）：
$speakRule
REASONING: （如果SPEAK为YES）你的思考过程，简述你考虑了哪些因素、如何权衡得出结论，150字以内
$contentFormat
$voteRule
TAGS: 用逗号分隔（如 PRO,RISK,STRATEGY）"""
    }

    // ── Prompt 构建辅助方法 ──────────────────────────────────

    private fun buildPriorSummary(myMessages: List<BoardMessage>): String {
        if (myMessages.isEmpty()) return ""
        val summaryList = myMessages.joinToString("\n") { msg ->
            "  - 第${msg.round}轮：${msg.content.take(80)}…"
        }
        val antiRepeatRule = when (promptStyle) {
            "creative" -> """
⚠️ 不要重复已有的创意！你必须：
- 提出全新的创意方向
- 或在他人创意基础上延伸组合
- 或从不同角度审视问题"""
            "collaborative" -> """
⚠️ 不要重复已分享的信息！你必须：
- 补充新发现的事实或线索
- 或对他人的分析做出回应
- 或提出新的改进建议"""
            else -> """
⚠️ 严禁重复以上已表达的观点！你必须：
- 提供全新的角度、论据或数据
- 或回应他人最新的质疑/反驳
- 或基于讨论进展更新你的立场
- 如果与之前立场一致，必须给出新的支撑理由"""
        }
        return """
━━━━━━━━━━━━━━━━━━━━━━━━
你的历史发言摘要
━━━━━━━━━━━━━━━━━━━━━━━━
$summaryList
$antiRepeatRule"""
    }

    private fun buildInstruction(
        board: Blackboard,
        isFirstSpeech: Boolean,
        hasOtherSpeakers: Boolean,
        isEarlyRound: Boolean,
        quotes: String,
    ): String = when (promptStyle) {
        "creative" -> buildCreativeInstruction(board, isFirstSpeech, hasOtherSpeakers)
        "collaborative" -> buildCollaborativeInstruction(board, isFirstSpeech, hasOtherSpeakers)
        "pitch" -> buildPitchInstruction(board, isFirstSpeech, hasOtherSpeakers, quotes)
        else -> buildDebateOrReviewInstruction(
            board, isFirstSpeech, hasOtherSpeakers, isEarlyRound, quotes
        )
    }

    private fun buildCreativeInstruction(
        board: Blackboard, isFirstSpeech: Boolean, hasOtherSpeakers: Boolean,
    ): String = when {
        isFirstSpeech && board.messages.isEmpty() ->
            "头脑风暴开始！请大胆提出你从${presetRole.stance}视角出发的创意想法，不设限制，天马行空。"
        isFirstSpeech && hasOtherSpeakers ->
            "已有创意被提出。请从你的${presetRole.stance}视角出发，提出新的创意方向，或在已有创意基础上进行延伸和组合。"
        hasOtherSpeakers ->
            "请继续贡献！你可以：提出全新创意、将多个已有创意进行组合、从${presetRole.stance}视角重新审视已有方案、或指出被忽略的可能性。"
        else ->
            "请提出你的创意想法。"
    }

    private fun buildCollaborativeInstruction(
        board: Blackboard, isFirstSpeech: Boolean, hasOtherSpeakers: Boolean,
    ): String = when {
        isFirstSpeech && board.messages.isEmpty() ->
            "讨论开始。请从你的${presetRole.stance}视角出发，分享你掌握的关键信息和初步分析。"
        isFirstSpeech && hasOtherSpeakers ->
            "已有参与者分享了信息。请从你的${presetRole.stance}视角补充关键信息，对已有分析做出回应，或提出你的建议。"
        hasOtherSpeakers ->
            "讨论正在进行中。请补充新的信息或发现、对他人的分析提出反馈、或完善改进方案。避免仅表示同意，务必贡献实质内容。"
        else ->
            "请分享你的分析和建议。"
    }

    private fun buildPitchInstruction(
        board: Blackboard,
        isFirstSpeech: Boolean,
        hasOtherSpeakers: Boolean,
        quotes: String,
    ): String {
        val isAdvocate = presetRole.stance.uppercase().let {
            it.contains("ADVOCATE") || it.contains("支持") || it.contains("FOUNDER")
        }
        return when {
            isFirstSpeech && isAdvocate && board.messages.isEmpty() ->
                "请进行你的路演展示：介绍产品/服务、市场机会、商业模式、团队优势和发展规划。用数据和事实来支撑你的论述。"
            isFirstSpeech && isAdvocate && hasOtherSpeakers ->
                "评委已提出问题和质疑。请回应这些问题，用事实和数据来证明你的观点。"
            isFirstSpeech && hasOtherSpeakers ->
                "路演展示已完成。请从你的${presetRole.stance}视角进行评估，提出关键问题或给出专业分析。"
            hasOtherSpeakers && quotes.isNotBlank() ->
                "讨论正在进行。以下是需要关注的重要观点：\n$quotes\n请基于你的${presetRole.stance}视角做出回应。"
            hasOtherSpeakers ->
                "请继续从你的${presetRole.stance}视角补充评估意见、追问细节、或回应其他参与者的观点。"
            else ->
                "请发表你的观点。"
        }
    }

    private fun buildDebateOrReviewInstruction(
        board: Blackboard,
        isFirstSpeech: Boolean,
        hasOtherSpeakers: Boolean,
        isEarlyRound: Boolean,
        quotes: String,
    ): String {
        val actionVerb = if (promptStyle == "review") "评估" else "观点"
        return when {
            isFirstSpeech && board.messages.isEmpty() ->
                "这是讨论的开始，你必须发言。请从你的${presetRole.stance}视角出发，直接给出你的核心${actionVerb}。"
            isFirstSpeech && hasOtherSpeakers ->
                "已有其他参与者发言，你必须发言。请根据已有讨论，从你的${presetRole.stance}视角给出分析和${actionVerb}。如果有与你立场不同的论点，请明确回应。"
            hasOtherSpeakers && quotes.isNotBlank() -> if (promptStyle == "review") {
                "评审正在进行中。以下是其他评审者提出的需要关注的观点：\n$quotes\n请从你的${presetRole.stance}视角做出回应或补充。"
            } else {
                "讨论正在进行中。以下是其他参与者提出的与你立场不同的关键论点，请有针对性地回应：\n$quotes\n请深入分析这些论点的不足之处，并给出你的反驳。"
            }
            hasOtherSpeakers && isEarlyRound ->
                "讨论正在进行中，你必须发言。请根据最新发言补充观点、回应质疑或提供新的论据。"
            hasOtherSpeakers ->
                "讨论正在进行中。请根据最新发言补充观点、回应质疑或提供新的论据。如果你确实没有全新的信息，可以从不同角度重新审视已有论点。"
            else ->
                "请发表你的${actionVerb}。"
        }
    }

    // ── SPEAK 规则 ──

    private fun buildSpeakRule(isEarlyRound: Boolean, alwaysSpeak: Boolean): String = when {
        alwaysSpeak -> "SPEAK: YES （本模式要求所有参与者积极发言，不允许选择NO）"
        isEarlyRound -> "SPEAK: YES （前3轮必须发言，不允许选择NO）"
        else -> "SPEAK: YES 或 NO"
    }

    // ── CONTENT 格式（按 promptStyle 区分）──

    private fun buildContentFormat(): String {
        val header = "CONTENT: （如果SPEAK为YES）使用 **markdown 格式**，300-500字，必须遵循以下结构："
        val structure = when (promptStyle) {
            "creative" -> """
### 创意提案
你的创意想法（1-2句话点明核心概念）

### 创意描述
- **具体方案**：详细描述这个创意如何实现
- **独特价值**：为什么这个方向值得探索
- **灵感来源**：受到什么启发（可关联他人创意）

### 延伸与组合
如何与其他已有创意结合、进一步演化的可能性、或这个方向可能衍生的子创意"""

            "collaborative" -> """
### 关键发现
你从${presetRole.stance}视角发现的关键信息或事实（1-2句话）

### 分析与补充
- **事实/证据**：你掌握的具体信息
- **分析**：基于你的专业视角的判断
- **与他人发现的关联**：如何与其他参与者的分析相互印证或补充

### 建议方案
具体的改进措施或后续行动建议"""

            "pitch" -> """
### 核心观点
你的评估结论或展示要点（1-2句话）

### 支撑分析
- **论据1**：具体的数据、事实或专业判断
- **论据2**：具体的数据、事实或专业判断
- （如有更多继续列出）

### 回应与提问
回应他人的问题/质疑，或提出需要进一步澄清的关键问题"""

            "review" -> """
### 评估结论
你从${presetRole.stance}视角的评估结论（1-2句话）

### 分析依据
- **发现1**：具体的评估发现或依据
- **发现2**：具体的评估发现或依据
- （如有更多发现继续列出）

### 建议与关注点
改进建议、需要关注的风险点、或对其他评审意见的回应"""

            // "debate" or default
            else -> """
### 核心判断
你的明确立场和结论（1-2句话）

### 论据与数据
- **论据1**：具体的支撑依据
- **论据2**：具体的支撑依据
- （如有更多论据继续列出）

### 回应与反驳
针对其他参与者的观点进行评论或反驳（如果是首次发言，可改为"### 关键风险"或"### 需要关注的问题"）"""
        }
        return "$header\n$structure"
    }

    // ── VOTE 规则（按 voteType 区分）──

    private fun buildVoteRule(): String = when (voteType) {
        VoteType.SCALE ->
            "VOTE: 1-10 的整数评分（1=最差, 10=最优），例如 VOTE: 7"
        VoteType.MULTI_STANCE -> if (voteOptions.isNotEmpty()) {
            "VOTE: ${voteOptions.joinToString(" 或 ")}（从以上选项中选择一个）"
        } else {
            "VOTE: 你的立场标签（一个简短的词或短语）"
        }
        VoteType.BINARY ->
            "VOTE: AGREE 或 DISAGREE"
    }

    // ── 自我进化指引（按 promptStyle 区分）──

    private fun buildEvolutionGuide(): String {
        val missionItems = when (promptStyle) {
            "creative" -> """1. 从你的视角提出大胆且有价值的创意
2. 善于在他人创意基础上进行延伸和组合
3. 从历史讨论中学习哪些创意方向最受认可
4. 持续拓展你的创意思维和表达方式"""
            "collaborative" -> """1. 从你的专业视角提供准确的事实和深度分析
2. 积极回应并补充其他参与者的发现
3. 从历史讨论中学习哪些分析方法最有效
4. 持续提升你的调查和协作能力"""
            "pitch" -> """1. 从你的专业视角提供高质量的评估或展示
2. 善于提出有深度的关键问题
3. 从历史讨论中学习哪些论证模式最有说服力
4. 持续优化你的分析框架和沟通方式"""
            else -> """1. 从你的专业视角提供高质量的深度分析和判断
2. 主动识别并回应其他参与者的薄弱论据
3. 从历史讨论中学习哪些论证模式最有效
4. 持续优化你的分析框架和表达方式"""
        }
        return """━━━━━━━━━━━━━━━━━━━━━━━━
自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
$missionItems

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。"""
    }

    // ── 互引机制 — debate/review/pitch 模式 ──

    /**
     * 互引机制 — 提取对立方的具体论点供 Agent 精准回应
     *
     * 逻辑：基于 stance 推断对立 tags + 自身历史发言 tags，
     * 扩大匹配范围以确保互引机制生效。
     */
    private fun buildOpposingQuotes(board: Blackboard): String {
        // 基于 stance 推断自身 tags 和对立 tags
        val myTags = attentionTags.toSet()
        // 也从自身历史发言中收集 tags
        val myHistoryTags = board.messages
            .filter { it.role == role }
            .flatMap { it.normalizedTags }
            .toSet()
        val allMyTags = myTags + myHistoryTags

        val opposingTags = buildSet {
            // 正面 → 对立面
            if (allMyTags.any { it in setOf(MsgTag.PRO, MsgTag.GROWTH) }) {
                add(MsgTag.CON); add(MsgTag.RISK)
            }
            // 反面 → 对立面
            if (allMyTags.any { it in setOf(MsgTag.CON, MsgTag.RISK) }) {
                add(MsgTag.PRO); add(MsgTag.GROWTH)
            }
            // 策略/执行 → 关注风险和质疑
            if (allMyTags.any { it in setOf(MsgTag.STRATEGY, MsgTag.EXECUTION) }) {
                add(MsgTag.RISK); add(MsgTag.CON); add(MsgTag.QUESTION)
            }
            // 设计 → 关注可行性和质量
            if (allMyTags.any { it in setOf(MsgTag.DESIGN) }) {
                add(MsgTag.FEASIBILITY); add(MsgTag.QUALITY); add(MsgTag.CON)
            }
            // 质量/安全 → 关注支持和设计
            if (allMyTags.any { it in setOf(MsgTag.QUALITY, MsgTag.FEASIBILITY) }) {
                add(MsgTag.PRO); add(MsgTag.DESIGN)
            }
        }

        // 若推断不出对立面，取所有非自身、非 supervisor 的近期发言
        val myLastRound = board.messages.lastOrNull { it.role == role }?.round ?: 0
        val recentOpposing = if (opposingTags.isNotEmpty()) {
            board.messages.filter { msg ->
                msg.role != role &&
                msg.role != "supervisor" &&
                msg.round >= myLastRound &&
                msg.normalizedTags.any { it in opposingTags }
            }.takeLast(3)
        } else {
            // 无法推断对立面时，取所有其他人近期发言
            board.messages.filter { msg ->
                msg.role != role &&
                msg.role != "supervisor" &&
                msg.round >= maxOf(myLastRound, board.round - 2)
            }.takeLast(3)
        }

        if (recentOpposing.isEmpty()) return ""

        return recentOpposing.joinToString("\n") { msg ->
            "  ▸ [${msg.role}] ${msg.content.take(150)}"
        }
    }

    // ── collaborative/creative 模式取近期发言做参考 ──

    private fun buildRecentContext(board: Blackboard): String {
        val recent = board.messages
            .filter { it.role != role && it.role != "supervisor" }
            .takeLast(4)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { msg ->
            "  ▸ [${msg.role}] ${msg.content.take(150)}"
        }
    }
}

// ── Stance → AttentionTags 推断 ──────────────────────────────

/**
 * 根据 PresetRole.stance 关键词推断 attentionTags。
 * 使得 scoring 的 tag 匹配度和互引机制能真正生效。
 */
private fun inferAttentionTags(stance: String): List<MsgTag> {
    val s = stance.uppercase()
    val tags = mutableSetOf<MsgTag>()

    // 看多 / Bull / Advocate / For
    if (s.contains("BULL") || s.contains("看多") || s.contains("多头") ||
        s.contains("ADVOCATE") || s.contains("FOR") || s.contains("支持")) {
        tags.addAll(listOf(MsgTag.PRO, MsgTag.GROWTH))
    }
    // 看空 / Bear / Against / Risk
    if (s.contains("BEAR") || s.contains("看空") || s.contains("空头") ||
        s.contains("AGAINST") || s.contains("反对") || s.contains("RISK") ||
        s.contains("风险")) {
        tags.addAll(listOf(MsgTag.CON, MsgTag.RISK))
    }
    // 中立 / Neutral / Framework / Strategy
    if (s.contains("NEUTRAL") || s.contains("中立") || s.contains("FRAMEWORK") ||
        s.contains("STRATEGY") || s.contains("策略") || s.contains("FACILITATION")) {
        tags.addAll(listOf(MsgTag.STRATEGY, MsgTag.GENERAL))
    }
    // 执行 / Execution
    if (s.contains("EXECUTION") || s.contains("执行") || s.contains("ACTION")) {
        tags.addAll(listOf(MsgTag.EXECUTION, MsgTag.STRATEGY))
    }
    // 事实 / Facts / Investigation / Info
    if (s.contains("FACTS") || s.contains("事实") || s.contains("INVESTIGATION") ||
        s.contains("情报") || s.contains("INFO")) {
        tags.addAll(listOf(MsgTag.NEWS, MsgTag.GENERAL))
    }
    // 裁判 / Adjudication / Review
    if (s.contains("ADJUDICATION") || s.contains("裁判") || s.contains("REVIEW") ||
        s.contains("评审")) {
        tags.addAll(listOf(MsgTag.STRATEGY, MsgTag.GENERAL))
    }
    // 设计 / UX / Design
    if (s.contains("DESIGN") || s.contains("设计") || s.contains("UX") ||
        s.contains("用户体验")) {
        tags.addAll(listOf(MsgTag.DESIGN, MsgTag.QUALITY))
    }
    // 可行性 / Feasibility / Performance / Security
    if (s.contains("FEASIBILITY") || s.contains("可行性") || s.contains("PERFORMANCE") ||
        s.contains("性能") || s.contains("SECURITY") || s.contains("安全")) {
        tags.addAll(listOf(MsgTag.FEASIBILITY, MsgTag.RISK))
    }
    // 质量 / Quality / QA
    if (s.contains("QUALITY") || s.contains("质量") || s.contains("QA") ||
        s.contains("测试")) {
        tags.addAll(listOf(MsgTag.QUALITY, MsgTag.RISK))
    }
    // 需求 / Requirements / User Perspective
    if (s.contains("REQUIREMENTS") || s.contains("需求") || s.contains("USER") ||
        s.contains("用户")) {
        tags.addAll(listOf(MsgTag.PRO, MsgTag.FEASIBILITY))
    }
    // Methodology / Novelty (paper review)
    if (s.contains("METHODOLOGY") || s.contains("方法论") || s.contains("NOVELTY") ||
        s.contains("IMPACT")) {
        tags.addAll(listOf(MsgTag.QUALITY, MsgTag.STRATEGY))
    }
    // Scrutiny / Due Diligence (startup pitch, legal)
    if (s.contains("SCRUTINY") || s.contains("COMPLIANCE") || s.contains("合规") ||
        s.contains("DUE DILIGENCE")) {
        tags.addAll(listOf(MsgTag.RISK, MsgTag.CON, MsgTag.QUALITY))
    }
    // Data / Market (startup pitch)
    if (s.contains("DATA") || s.contains("数据") || s.contains("MARKET") ||
        s.contains("市场")) {
        tags.addAll(listOf(MsgTag.NEWS, MsgTag.GROWTH))
    }
    // Guidance / Mentor
    if (s.contains("GUIDANCE") || s.contains("指导") || s.contains("MENTOR") ||
        s.contains("导师")) {
        tags.addAll(listOf(MsgTag.STRATEGY, MsgTag.PRO))
    }
    // Technical (engineering)
    if (s.contains("TECHNICAL") && !s.contains("技术面")) {
        tags.addAll(listOf(MsgTag.QUALITY, MsgTag.FEASIBILITY))
    }
    // Systems / SRE
    if (s.contains("SYSTEMS") || s.contains("系统") || s.contains("SRE") ||
        s.contains("INFRASTRUCTURE")) {
        tags.addAll(listOf(MsgTag.FEASIBILITY, MsgTag.RISK))
    }
    // Wild Ideas / Innovation (brainstorm)
    if (s.contains("WILD") || s.contains("INNOVATION") || s.contains("创新") ||
        s.contains("创意") || s.contains("CREATIVE")) {
        tags.addAll(listOf(MsgTag.PRO, MsgTag.STRATEGY))
    }
    // Integration / Synthesis
    if (s.contains("INTEGRATION") || s.contains("整合") || s.contains("SYNTHESIS")) {
        tags.addAll(listOf(MsgTag.STRATEGY, MsgTag.GENERAL))
    }
    // Need / User voice
    if (s.contains("NEED") || s.contains("痛点") || s.contains("PAIN")) {
        tags.addAll(listOf(MsgTag.PRO, MsgTag.FEASIBILITY))
    }

    // 如果没有匹配到任何 tag，fallback 到 GENERAL
    return tags.ifEmpty { mutableSetOf(MsgTag.GENERAL) }.toList()
}
