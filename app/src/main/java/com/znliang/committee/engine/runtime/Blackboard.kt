package com.znliang.committee.engine.runtime

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Blackboard — Agent 共享环境（v5：不可变）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v5 关键修复：
 *    messages 和 votes 改为不可变 List
 *    每次更新都创建新 List → StateFlow 能正确检测变化 → UI 更新
 */

enum class PreSearchStatus { IDLE, SEARCHING, DONE, FAILED }

data class Blackboard(
    val subject: String = "",
    val round: Int = 1,
    val maxRounds: Int = 20,
    val messages: List<BoardMessage> = emptyList(),        // 不可变
    val votes: Map<String, BoardVote> = emptyMap(),        // role → latest vote（去重）
    val phase: BoardPhase = BoardPhase.IDLE,
    val consensus: Boolean = false,
    val finished: Boolean = false,
    val finalRating: String? = null,
    val executionPlan: String? = null,
    val summary: String = "",
    val lastSummaryRound: Int = 0,
    /** 情报官预搜索结果（会议开始前基于历史缺口自动搜索） */
    val preGatheredInfo: String = "",
    /** 多模态附件材料（图片/文件） */
    val materials: List<MaterialRef> = emptyList(),
    /** Agent 发言贡献度评分（roleId → ContributionScore） */
    val contributionScores: Map<String, ContributionScore> = emptyMap(),
    /** 用户设置的 Agent 话语权重（roleId → weight, default 1.0） */
    val userWeights: Map<String, Float> = emptyMap(),
    /** 决策置信度（0-100），会议结束时计算 */
    val decisionConfidence: Int = 0,
    /** 置信度说明 */
    val confidenceBreakdown: String = "",
    /** 用户覆写的最终评级（null = 采纳 Agent 结论） */
    val userOverrideRating: String? = null,
    /** 用户覆写理由 */
    val userOverrideReason: String = "",
    /** 错误信息（API 失败等，非空时表示会议因错误终止） */
    val errorMessage: String? = null,
    /** 初始阶段（用于 startMeeting 时立即推进 UI，不等 inferPhase） */
    val initialPhase: BoardPhase? = null,
    /** 会前情报搜索状态 */
    val preSearchStatus: PreSearchStatus = PreSearchStatus.IDLE,
    /** 进化通知（非空时 UI 显示 Snackbar） */
    val evolutionNotification: String = "",
) {
    fun agreeRatio(): Float {
        if (votes.isEmpty()) return 0f
        var agreeWeight = 0f
        var totalWeight = 0f
        for ((_, vote) in votes) {
            val w = 1f  // 统一权重，不再对 human 特殊加权
            if (vote.agree) agreeWeight += w
            totalWeight += w
        }
        return if (totalWeight > 0f) agreeWeight / totalWeight else 0f
    }

    fun disagreeRatio(): Float = if (votes.isEmpty()) 0f else 1f - agreeRatio()

    @Deprecated("Use agreeRatio()", replaceWith = ReplaceWith("agreeRatio()"))
    fun bullRatio(): Float = agreeRatio()

    @Deprecated("Use disagreeRatio()", replaceWith = ReplaceWith("disagreeRatio()"))
    fun bearRatio(): Float = disagreeRatio()

    fun hasConsensus(voteType: VoteType = VoteType.BINARY): Boolean = when (voteType) {
        VoteType.BINARY -> votes.size >= 3 && (agreeRatio() > 0.7f || disagreeRatio() > 0.7f)
        VoteType.SCALE -> {
            // SCALE consensus: scores within 2 points of each other
            val scores = votes.values.mapNotNull { it.numericScore }
            scores.size >= 2 && (scores.max() - scores.min()) <= 2
        }
        VoteType.MULTI_STANCE -> {
            // MULTI_STANCE consensus: majority (>50%) agree on the same stance
            val stances = votes.values.mapNotNull { it.stanceLabel }
            if (stances.isEmpty()) false
            else {
                val grouped = stances.groupingBy { it }.eachCount()
                val maxCount = grouped.values.maxOrNull() ?: 0
                maxCount > stances.size / 2
            }
        }
    }

    /** Average numeric score for SCALE mode */
    fun averageScore(): Float {
        val scores = votes.values.mapNotNull { it.numericScore }
        return if (scores.isEmpty()) 0f else scores.average().toFloat()
    }

    /** Majority stance label for MULTI_STANCE mode */
    fun majorityStance(): String? {
        val stances = votes.values.mapNotNull { it.stanceLabel }
        if (stances.isEmpty()) return null
        return stances.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    fun inferPhase(): BoardPhase = when {
        finished && executionPlan != null -> BoardPhase.DONE
        finished && finalRating != null -> BoardPhase.EXECUTION
        finalRating != null -> BoardPhase.RATING
        votes.size >= 2 -> BoardPhase.VOTE
        round > 1 || messages.size > 3 -> BoardPhase.DEBATE
        messages.isEmpty() && initialPhase != null -> initialPhase
        messages.isEmpty() -> BoardPhase.IDLE
        else -> BoardPhase.ANALYSIS
    }

    fun messagesByTags(vararg tags: MsgTag): List<BoardMessage> {
        if (tags.isEmpty()) return messages
        return messages.filter { msg -> msg.normalizedTags.any { it in tags } }
    }

    fun contextForAgent(agent: Agent): String {
        val sb = StringBuilder()
        // 注入当前日期（所有 agent 可见，搜索/判断时必须知道今天几号）
        val today = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", java.util.Locale.CHINA))
        sb.appendLine("【当前日期】$today")
        sb.appendLine()
        // 多模态附件材料描述
        if (materials.isNotEmpty()) {
            sb.appendLine("【附件材料】（共${materials.size}份，图片已通过视觉输入提供）")
            materials.forEachIndexed { idx, mat ->
                val desc = if (mat.description.isNotBlank()) " — ${mat.description}" else ""
                sb.appendLine("  ${idx + 1}. ${mat.fileName} (${mat.mimeType})$desc")
            }
            sb.appendLine()
        }
        // 情报官预搜索结果注入（所有 agent 可见，但情报官尤其关注）
        if (preGatheredInfo.isNotBlank()) {
            sb.appendLine(preGatheredInfo)
            sb.appendLine()
        }
        if (summary.isNotBlank()) {
            sb.appendLine("【讨论摘要】")
            sb.appendLine(summary)
            sb.appendLine()
        }
        val relevant = agent.relevantMessages(this)
        if (relevant.isNotEmpty()) {
            sb.appendLine("【近期相关发言】")
            relevant.forEach { msg ->
                sb.appendLine("[${msg.role}] ${msg.content.take(150)}")
            }
        }
        return sb.toString()
    }
}
