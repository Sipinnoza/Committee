package com.znliang.committee.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  具体 Agent（v5 — EvolvableAgent）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v5 变化：全部实现 EvolvableAgent 接口
 *    - recallRelevantExperience()：从进化库回忆经验
 *    - enrichPrompt()：将经验注入 prompt
 *    - 默认实现在 EvolvableAgent 接口中，Agent 无需覆写
 */

class AnalystAgent : EvolvableAgent {
    override val role = "analyst"
    override val displayName = "分析师"
    override val attentionTags = listOf(MsgTag.BEAR, MsgTag.RISK, MsgTag.VALUATION, MsgTag.GROWTH, MsgTag.NEWS)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    /**
     * 🔥 Hermes 式评分 — 分析师看多视角
     *   - 被看空反驳时应该积极回应
     *   - 有新的看多证据时应主动发言
     *   - 已充分表达且无新信息时安静
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0
        val myMessages = board.messages.filter { it.role == role }
        val riskMessages = board.messages.filter { it.role == "risk_officer" }

        // ① 第一轮轮到看多方时优先发言
        if (board.messages.isEmpty() || (board.messages.size == 1 && board.messages[0].role == "intel")) {
            return 18.0
        }

        // ② 被看空方反驳 → 高分回应
        val recentRiskArgs = riskMessages
            .takeLast(3)
            .flatMap { it.normalizedTags }
            .count { it == MsgTag.BEAR || it == MsgTag.RISK }
        score += recentRiskArgs * 4.0

        // ③ 有新信息出现（情报官刚发了消息）
        val lastIntelRound = board.messages.lastOrNull { it.role == "intel" }?.round ?: 0
        val myLastRound = myMessages.lastOrNull()?.round ?: 0
        if (lastIntelRound > myLastRound) score += 6.0

        // ④ 轮间未发言轮数
        val roundsSince = board.round - myLastRound
        score += minOf(roundsSince, 4) * 2.0

        // ⑤ 本轮已发言 → 降分
        score -= board.messages.count { it.role == role && it.round == board.round } * 5.0

        // ⑥ 已充分表达 → 降分
        if (myMessages.size >= 3 && recentRiskArgs == 0) score -= 3.0

        return score
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val mustSpeak = board.messages.isEmpty() ||
            (board.messages.size == 1 && board.messages[0].role == "intel")
        val instruction = if (mustSpeak) {
            "这是第一轮，你必须发言。直接给出你的看多分析。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有讨论进展，请根据最新信息判断是否需要补充看多观点。如果有人提出看空理由，你应当反驳。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是【看多分析师｜超级智能体】。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

━━━━━━━━━━━━━━━━━━━━━━━━
分析师自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 提供扎实的看多论证（基本面/技术面/估值/催化剂）
2. 主动识别并反驳看空方的薄弱论据
3. 从历史会议中学习哪些看多论证模式最有效
4. 持续优化你的估值锚点和逻辑链

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的看多分析，包括基本面/技术面/估值/催化剂，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 BULL,VALUATION,GROWTH）"""
    }
}

class RiskAgent : EvolvableAgent {
    override val role = "risk_officer"
    override val displayName = "风险官"
    override val attentionTags = listOf(MsgTag.BULL, MsgTag.GROWTH, MsgTag.VALUATION, MsgTag.NEWS, MsgTag.TECHNICAL)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    /**
     * 🔥 Hermes 式评分 — 风险官看空视角
     *   - 被看多方反驳时应积极回应
     *   - 检测到新风险信号时主动发言
     *   - 已充分揭示风险且无新威胁时安静
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0
        val myMessages = board.messages.filter { it.role == role }
        val analystMessages = board.messages.filter { it.role == "analyst" }

        // ① 情报官发言后轮到看空方
        if (board.messages.size <= 2 && myMessages.isEmpty()) {
            return 16.0
        }

        // ② 被看多方反驳 → 高分回应
        val recentBullArgs = analystMessages
            .takeLast(3)
            .flatMap { it.normalizedTags }
            .count { it == MsgTag.BULL || it == MsgTag.GROWTH || it == MsgTag.VALUATION }
        score += recentBullArgs * 4.0

        // ③ 有新风险信号出现（新闻/技术面变化）
        val lastIntelRound = board.messages.lastOrNull { it.role == "intel" }?.round ?: 0
        val myLastRound = myMessages.lastOrNull()?.round ?: 0
        if (lastIntelRound > myLastRound) score += 6.0

        // ④ 轮间未发言轮数
        val roundsSince = board.round - myLastRound
        score += minOf(roundsSince, 4) * 2.0

        // ⑤ 本轮已发言 → 降分
        score -= board.messages.count { it.role == role && it.round == board.round } * 5.0

        // ⑥ 已充分揭示风险 → 降分
        if (myMessages.size >= 3 && recentBullArgs == 0) score -= 3.0

        return score
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val mustSpeak = board.messages.isEmpty() ||
            (board.messages.size == 1 && board.messages[0].role == "intel")
        val instruction = if (mustSpeak) {
            "这是第一轮，你必须发言。直接给出你的风险分析。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有讨论进展，请根据最新信息判断是否需要补充风险观点。如果有人提出看多理由，你应当指出风险。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是【风险官（看空方）｜超级智能体】。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

━━━━━━━━━━━━━━━━━━━━━━━━
风险官自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 全面揭示投资风险（财务/行业/技术面/宏观）
2. 主动质疑看多方的薄弱论据和过度乐观假设
3. 从历史会议中学习哪些风险最容易遗漏
4. 持续优化你的假设攻击深度和看空论证模式

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的风险分析，包括财务风险/行业风险/技术面风险/宏观风险，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 BEAR,RISK）"""
    }
}

class StrategistAgent : EvolvableAgent {
    override val role = "strategy_validator"
    override val displayName = "策略师"
    override val attentionTags = listOf(MsgTag.BULL, MsgTag.BEAR, MsgTag.RISK, MsgTag.GROWTH, MsgTag.VALUATION)
    override val canVote = true

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        return board.messages.count { it.role == role && it.round == board.round } < 2
    }

    /**
     * 🔥 Hermes 式评分 — 策略师中立视角
     *   - 多空充分辩论后才应发言
     *   - 检测到逻辑矛盾时主动介入
     *   - 辩论不充分时保持沉默
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0
        val myMessages = board.messages.filter { it.role == role }
        val debateMessages = board.messages.filter {
            it.role == "analyst" || it.role == "risk_officer"
        }

        // ① 辩论不充分 → 不发言（负分）
        if (debateMessages.size < 3) return -10.0

        // ② 多空都已发言至少2次 → 策略师介入时机
        val bullCount = debateMessages.count { it.role == "analyst" }
        val bearCount = debateMessages.count { it.role == "risk_officer" }
        if (bullCount >= 2 && bearCount >= 2) score += 12.0

        // ③ 轮间未发言轮数
        val myLastRound = myMessages.lastOrNull()?.round ?: 0
        val roundsSince = board.round - myLastRound
        score += minOf(roundsSince, 5) * 2.0

        // ④ 本轮已发言 → 降分
        score -= board.messages.count { it.role == role && it.round == board.round } * 5.0

        // ⑤ 已给出策略 → 降分
        if (myMessages.size >= 2) score -= 4.0

        return score
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val hasDebate = board.messages.count { it.role != "supervisor" } >= 2
        val instruction = if (!hasDebate) {
            "多空双方尚未充分发言，你暂时不需要发言。"
        } else if (context.isNotBlank() && context.lines().any { it.isNotBlank() }) {
            "已有充分讨论，请基于多空观点给出策略建议。"
        } else {
            "请判断是否需要发言。"
        }
        return """你是【策略师（中立）｜超级智能体】。标的：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

━━━━━━━━━━━━━━━━━━━━━━━━
策略师自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 在多空充分辩论后给出客观的策略建议
2. 识别多空双方论证中的逻辑矛盾
3. 从历史会议中学习哪些分析框架最适合哪类标的
4. 持续优化入场/出场评估和风险收益比判断

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的策略建议，包括多空权衡/入场出场价位/风险收益比/仓位建议，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 STRATEGY,VALUATION）"""
    }
}

class IntelAgent : EvolvableAgent {
    override val role = "intel"
    override val displayName = "情报官"
    override val attentionTags = listOf(MsgTag.NEWS, MsgTag.GENERAL, MsgTag.TECHNICAL, MsgTag.BULL, MsgTag.BEAR)
    override val canVote = true

    /** 情报官特权：可以通过 LLM 自主判断是否需要使用工具获取数据 */
    val canUseTools: Boolean = true

    /** 情报官特权：比其他 Agent 更多的发言次数（情报可能需要多次补充） */
    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val myMessages = board.messages.count { it.role == role }
        // 第一轮必须有情报；后续轮次允许最多 5 次补充
        return myMessages < 5
    }

    /**
     * 🔥 Hermes 式评分 — 情报官获得更高优先级
     *
     * 参考 Hermes 的 Memory prefetch 机制：
     *   - 情报是讨论的基础，信息缺口时应优先发言
     *   - 有新信息需求时（别人引用了未核实数据）应主动补充
     *   - 信息充足时应该安静，不浪费 token
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0

        // ① 第一轮：情报官必须先发言，给最高优先级
        if (board.messages.isEmpty()) {
            return 20.0  // 远高于其他 Agent
        }

        // ② 信息缺口检测：其他 Agent 讨论了但情报官还没提供基础数据
        val hasIntelReport = board.messages.any { it.role == role }
        if (!hasIntelReport && board.messages.size >= 1) {
            score += 15.0  // 信息缺口，紧急补充
        }

        // ③ 未核实数据检测：其他 Agent 引用了数据但没标注来源
        val unverifiedClaims = board.messages
            .filter { it.role != role && it.role != "supervisor" }
            .count { msg ->
                val c = msg.content
                c.contains("预计") || c.contains("约") || c.contains("大概") ||
                c.contains("%") || c.contains("亿") || c.contains("万")
            }
        if (unverifiedClaims > 0 && !hasIntelReport) {
            score += 10.0
        }

        // ④ 轮间未发言轮数（越久没补充越该检查）
        val lastSpokeRound = board.messages.lastOrNull { it.role == role }?.round ?: 0
        val roundsSince = board.round - lastSpokeRound
        score += minOf(roundsSince, 5) * 1.5

        // ⑤ 最近有新话题出现（新 TAG 出现）
        val myTags = board.messages
            .filter { it.role == role }
            .flatMap { it.normalizedTags }
            .toSet()
        val recentNewTags = board.messages
            .takeLast(4)
            .filter { it.role != role }
            .flatMap { it.normalizedTags }
            .filter { it !in myTags && it != MsgTag.GENERAL }
            .distinct()
        score += recentNewTags.size * 3.0

        // ⑥ 本轮已发言 → 降分
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        score -= spokenThisRound * 4.0

        // ⑦ 信息充足时安静（已有 3+ 条情报且无新话题）
        if (board.messages.count { it.role == role } >= 3 && recentNewTags.isEmpty()) {
            score -= 5.0
        }

        return score
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)
        val myPreviousIntel = board.messages.filter { it.role == role }
        val hasIntel = myPreviousIntel.isNotEmpty()
        val intelCount = myPreviousIntel.size

        val phaseInstruction = when {
            !hasIntel -> """
━━━━━━━━━━━━━━━━━━━━━━━━
[信息收集阶段 — 首次情报报告]
━━━━━━━━━━━━━━━━━━━━━━━━
这是首次讨论，你必须发言。提供该标的的全面市场情报。

你必须尽可能覆盖以下维度：
1. 最新价格/涨跌幅/成交量
2. 近期重大新闻/公告/政策
3. 行业动态和竞争格局
4. 关键财务指标（如可获取）
5. 市场情绪和资金流向

如需获取实时数据，请使用 web_search 工具搜索以下关键词：
- "${board.subject} 最新消息"
- "${board.subject} 财报 业绩"
- "${board.subject} 行业分析"
""".trimIndent()

            intelCount == 1 -> """
━━━━━━━━━━━━━━━━━━━━━━━━
[信息补充阶段]
━━━━━━━━━━━━━━━━━━━━━━━━
你已经提供了一轮基础情报。请评估是否有重大遗漏需要补充。

重点关注：
- 其他 Agent 是否引用了未经核实的数据？
- 是否有遗漏的关键风险/机会？
- 行业/宏观层面是否有新的变化？

如需补充数据，请使用 web_search 工具搜索相关信息。
如认为信息已完备，回复 SPEAK: NO。
""".trimIndent()

            else -> """
━━━━━━━━━━━━━━━━━━━━━━━━
[信息更新阶段]
━━━━━━━━━━━━━━━━━━━━━━━━
你已经提供了 ${intelCount} 轮情报。请判断是否有**重大新信息**需要补充。

仅在以下情况发言：
- 出现了你之前未覆盖的重大新话题
- 其他 Agent 引用了明显错误或过时的数据需要纠正
- 有突发的市场事件需要紧急通报

如果信息储备充足，回复 SPEAK: NO。
""".trimIndent()
        }

        return """你是【情报官｜超级智能体】。标的：${board.subject}
轮次：${board.round}

$context

$phaseInstruction

━━━━━━━━━━━━━━━━━━━━━━━━
情报官自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 确保决策所需的信息完备且准确
2. 主动发现信息缺口并及时填补
3. 从历史会议中学习，记住哪些信息源最可靠
4. 持续优化信息获取策略

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）结构化情报报告，使用以下格式：

【数据快照】（价格/涨跌/成交量等核心数据）
【关键事件】（近期影响标的重要事件）
【信息缺口】（本次无法获取或存疑的关键数据项，格式：缺口→影响→建议补充方式）
【情报可信度】高/中/低

VOTE: BULL 或 BEAR（基于数据倾向，如无明确方向可投 BEAR 表示谨慎）
TAGS: 用逗号分隔（如 NEWS,TECHNICAL,GROWTH）"""
    }
}

class ExecutorAgent : EvolvableAgent {
    override val role = "executor"
    override val displayName = "执行员"
    override val attentionTags = listOf(MsgTag.STRATEGY, MsgTag.EXECUTION, MsgTag.BULL, MsgTag.BEAR)
    override val canVote = false

    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val hasRating = board.finalRating != null
        val hasConsensus = board.consensus
        return (hasRating || hasConsensus) && board.executionPlan == null
    }

    /**
     * 🔥 Hermes 式评分 — 执行员
     *   - 只在评级/共识出现后高分
     *   - 已有执行计划后不再发言
     */
    override fun scoring(board: Blackboard): Double {
        // 无评级无共识 → 不发言
        if (board.finalRating == null && !board.consensus) return -20.0
        // 已有执行计划 → 不发言
        if (board.executionPlan != null) return -20.0
        // 有评级或共识 → 高分
        return 15.0
    }

    override fun buildUnifiedPrompt(board: Blackboard): String {
        val rating = board.finalRating ?: "Hold"
        val context = board.contextForAgent(this)
        return """你是【执行官｜超级智能体】。标的：${board.subject}
最终评级：$rating

$context

请判断是否需要制定执行计划（通常在有明确评级后才需要）。

━━━━━━━━━━━━━━━━━━━━━━━━
执行员自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 将评级转化为具体可执行的操作方案
2. 设定精确的证伪条件（F1-F4）和回顾触发点
3. 从历史会议中学习执行偏差模式
4. 持续优化仓位管理和止损止盈策略

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）具体执行方案：操作方向/仓位/入场价位/止损止盈/分批策略
TAGS: EXECUTION,STRATEGY"""
    }
}
