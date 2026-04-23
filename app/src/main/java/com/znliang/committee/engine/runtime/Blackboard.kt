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

// ── 标签标准化 ──────────────────────────────────────────────

enum class MsgTag(val keys: List<String>) {
    // ── 投资领域标签（后向兼容）──
    BULL(listOf("BULL", "看多", "利好", "买入机会", "多头", "BULLISH")),
    BEAR(listOf("BEAR", "看空", "利空", "回避", "空头", "BEARISH")),
    VALUATION(listOf("VALUATION", "估值", "PE", "EPS", "PB", "DCF", "市盈率", "市净率")),
    GROWTH(listOf("GROWTH", "增长", "营收", "利润", "增速", "成长", "CAGR")),
    TECHNICAL(listOf("TECHNICAL", "技术面", "均线", "支撑", "阻力", "MACD", "RSI", "K线")),

    // ── 通用决策标签 ──
    PRO(listOf("PRO", "赞成", "支持", "优势", "APPROVE", "FOR", "AGREE", "SUPPORT")),
    CON(listOf("CON", "反对", "质疑", "劣势", "REJECT", "AGAINST", "DISAGREE", "OPPOSE")),
    RISK(listOf("RISK", "风险", "下跌", "止损", "亏损", "危机", "隐患", "WARNING")),
    NEWS(listOf("NEWS", "新闻", "公告", "事件", "政策", "行业动态", "EVENT", "INFO")),
    STRATEGY(listOf("STRATEGY", "策略", "仓位", "入场", "出场", "配置", "方案", "PLAN")),
    EXECUTION(listOf("EXECUTION", "执行", "操作", "买入", "卖出", "下单", "ACTION", "IMPLEMENT")),
    QUESTION(listOf("QUESTION", "问题", "疑问", "待确认", "ASK", "CLARIFY", "QUERY")),
    DESIGN(listOf("DESIGN", "设计", "架构", "方案", "ARCHITECTURE", "PROPOSAL")),
    QUALITY(listOf("QUALITY", "质量", "测试", "BUG", "缺陷", "TEST", "REVIEW")),
    FEASIBILITY(listOf("FEASIBILITY", "可行性", "成本", "资源", "时间", "COST", "RESOURCE")),
    GENERAL(listOf("GENERAL", "一般", "综合"));

    companion object {
        fun normalize(raw: String): MsgTag {
            val upper = raw.trim().uppercase()
            return entries.firstOrNull { tag ->
                tag.keys.any { key -> upper.contains(key) }
            } ?: GENERAL
        }

        fun normalizeAll(rawTags: List<String>): List<MsgTag> {
            if (rawTags.isEmpty()) return listOf(GENERAL)
            return rawTags.map { normalize(it) }.distinct()
        }
    }
}

// ── 附件引用 ────────────────────────────────────────────────

data class MaterialRef(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val description: String = "",
    val base64: String = "",    // 压缩后 base64，运行时填充
)

// ── Blackboard（不可变）───────────────────────────────────────

data class Blackboard(
    val subject: String = "",
    val round: Int = 1,
    val maxRounds: Int = 20,
    val messages: List<BoardMessage> = emptyList(),        // 🔥 不可变
    val votes: Map<String, BoardVote> = emptyMap(),        // 🔥 role → latest vote（去重）
    val phase: BoardPhase = BoardPhase.IDLE,
    val consensus: Boolean = false,
    val finished: Boolean = false,
    val finalRating: String? = null,
    val executionPlan: String? = null,
    val summary: String = "",
    val lastSummaryRound: Int = 0,
    /** 🔥 情报官预搜索结果（会议开始前基于历史缺口自动搜索） */
    val preGatheredInfo: String = "",
    /** 🔥 多模态附件材料（图片/文件） */
    val materials: List<MaterialRef> = emptyList(),
) {
    fun bullRatio(): Float {
        if (votes.isEmpty()) return 0f
        var agreeWeight = 0f
        var totalWeight = 0f
        for ((_, vote) in votes) {
            val w = if (vote.role == "human") 1.5f else 1f
            if (vote.agree) agreeWeight += w
            totalWeight += w
        }
        return if (totalWeight > 0f) agreeWeight / totalWeight else 0f
    }

    fun bearRatio(): Float = if (votes.isEmpty()) 0f else 1f - bullRatio()

    fun hasConsensus(): Boolean =
        votes.size >= 3 && (bullRatio() > 0.7f || bearRatio() > 0.7f)

    fun inferPhase(): BoardPhase = when {
        finished && executionPlan != null -> BoardPhase.DONE
        finished && finalRating != null -> BoardPhase.EXECUTION
        finalRating != null -> BoardPhase.RATING
        votes.size >= 2 -> BoardPhase.VOTE
        round > 1 || messages.size > 3 -> BoardPhase.DEBATE
        messages.isEmpty() -> BoardPhase.IDLE
        else -> BoardPhase.ANALYSIS
    }

    fun messagesByTags(vararg tags: MsgTag): List<BoardMessage> {
        if (tags.isEmpty()) return messages
        return messages.filter { msg -> msg.normalizedTags.any { it in tags } }
    }

    fun contextForAgent(agent: Agent): String {
        val sb = StringBuilder()
        // 🔥 注入当前日期（所有 agent 可见，搜索/判断时必须知道今天几号）
        val today = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", java.util.Locale.CHINA))
        sb.appendLine("【当前日期】$today")
        sb.appendLine()
        // 🔥 多模态附件材料描述
        if (materials.isNotEmpty()) {
            sb.appendLine("【附件材料】（共${materials.size}份，图片已通过视觉输入提供）")
            materials.forEachIndexed { idx, mat ->
                val desc = if (mat.description.isNotBlank()) " — ${mat.description}" else ""
                sb.appendLine("  ${idx + 1}. ${mat.fileName} (${mat.mimeType})$desc")
            }
            sb.appendLine()
        }
        // 🔥 情报官预搜索结果注入（所有 agent 可见，但情报官尤其关注）
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

data class BoardMessage(
    val role: String,
    val content: String,
    val round: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val rawTags: List<String> = emptyList(),
    val normalizedTags: List<MsgTag> = MsgTag.normalizeAll(rawTags),
)

data class BoardVote(
    val role: String,
    val agree: Boolean,
    val reason: String = "",
    val round: Int,
)

enum class BoardPhase {
    IDLE, ANALYSIS, DEBATE, VOTE, RATING, EXECUTION, DONE,
}

// ── UnifiedResponse ────────────────────────────────────────

data class UnifiedResponse(
    val wantsToSpeak: Boolean,
    val content: String,
    val voteBull: Boolean?,
    val rawTags: List<String>,
    val normalizedTags: List<MsgTag>,
) {
    companion object {
        fun parse(raw: String, canVote: Boolean): UnifiedResponse {
            val lines = raw.trim().lines()
            var speak = false
            var voteBull: Boolean? = null
            val rawTags = mutableListOf<String>()
            val contentLines = mutableListOf<String>()
            var inContent = false

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("SPEAK:", ignoreCase = true) -> {
                        inContent = false
                        val value = trimmed.substringAfter(":").trim().uppercase()
                        speak = value.startsWith("YES") || value.startsWith("Y")
                    }
                    trimmed.startsWith("CONTENT:", ignoreCase = true) -> {
                        inContent = true
                        val firstLine = trimmed.substringAfter(":").trim()
                        if (firstLine.isNotBlank()) contentLines.add(firstLine)
                    }
                    trimmed.startsWith("VOTE:", ignoreCase = true) -> {
                        inContent = false
                        if (canVote) {
                            val value = trimmed.substringAfter(":").trim().uppercase()
                            voteBull = value.contains("BULL") || value.startsWith("A")
                                    || value.contains("看多")
                        }
                    }
                    trimmed.startsWith("TAGS:", ignoreCase = true) -> {
                        inContent = false
                        val value = trimmed.substringAfter(":").trim()
                        rawTags.addAll(value.split(",", " ", "|")
                            .map { it.trim() }.filter { it.isNotBlank() })
                    }
                    inContent && trimmed.isNotBlank() -> {
                        contentLines.add(trimmed)
                    }
                }
            }

            val content = contentLines.joinToString("\n")
            val normalized = if (rawTags.isNotEmpty()) {
                MsgTag.normalizeAll(rawTags)
            } else if (content.isNotBlank()) {
                MsgTag.normalizeAll(inferRawTags(content))
            } else {
                listOf(MsgTag.GENERAL)
            }

            return UnifiedResponse(
                wantsToSpeak = speak,
                content = content,
                voteBull = voteBull,
                rawTags = rawTags,
                normalizedTags = normalized,
            )
        }

        private fun inferRawTags(content: String): List<String> {
            val result = mutableListOf<String>()
            val c = content.uppercase()
            // 投资领域
            if (c.contains("估值") || c.contains("PE") || c.contains("EPS")) result.add("VALUATION")
            if (c.contains("增长") || c.contains("营收") || c.contains("利润")) result.add("GROWTH")
            if (c.contains("看多") || c.contains("利好") || c.contains("买入机会")) result.add("BULL")
            if (c.contains("看空") || c.contains("利空") || c.contains("回避")) result.add("BEAR")
            if (c.contains("技术") || c.contains("均线") || c.contains("MACD")) result.add("TECHNICAL")
            // 通用决策
            if (c.contains("赞成") || c.contains("支持") || c.contains("APPROVE")) result.add("PRO")
            if (c.contains("反对") || c.contains("质疑") || c.contains("REJECT")) result.add("CON")
            if (c.contains("风险") || c.contains("下跌") || c.contains("止损") || c.contains("隐患")) result.add("RISK")
            if (c.contains("新闻") || c.contains("公告") || c.contains("事件")) result.add("NEWS")
            if (c.contains("策略") || c.contains("方案") || c.contains("入场")) result.add("STRATEGY")
            if (c.contains("执行") || c.contains("操作") || c.contains("ACTION")) result.add("EXECUTION")
            if (c.contains("问题") || c.contains("疑问") || c.contains("待确认")) result.add("QUESTION")
            if (c.contains("设计") || c.contains("架构") || c.contains("DESIGN")) result.add("DESIGN")
            if (c.contains("质量") || c.contains("测试") || c.contains("BUG")) result.add("QUALITY")
            if (c.contains("可行性") || c.contains("成本") || c.contains("资源")) result.add("FEASIBILITY")
            return result.distinct().ifEmpty { listOf("GENERAL") }
        }
    }
}
