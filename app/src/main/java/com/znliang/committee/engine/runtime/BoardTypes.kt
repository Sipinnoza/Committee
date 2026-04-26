package com.znliang.committee.engine.runtime

// ── 标签标准化 ──────────────────────────────────────────────

enum class MsgTag(val keys: List<String>) {
    // ── 投资领域标签（后向兼容）──
    @Deprecated("Use PRO instead — investment-domain alias retained for serialized data compatibility")
    BULL(listOf("BULL", "看多", "利好", "买入机会", "多头", "BULLISH")),
    @Deprecated("Use CON instead — investment-domain alias retained for serialized data compatibility")
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
    val id: Long,                    // 材料ID
    val fileName: String,            // 文件名
    val mimeType: String,            // MIME类型（如image/png）
    val description: String = "",    // 材料描述
    val base64: String = "",    // 压缩后 base64，运行时填充
)

// ── Board 数据类型 ──────────────────────────────────────────

data class BoardMessage(
    val role: String,                                                // 发言者角色ID
    val content: String,                                             // 发言内容
    val round: Int,                                                  // 所属轮次
    val timestamp: Long = System.currentTimeMillis(),                // 发言时间戳
    val rawTags: List<String> = emptyList(),                         // 原始标签列表
    val normalizedTags: List<MsgTag> = MsgTag.normalizeAll(rawTags), // 标准化后的标签
)

/** 投票类型 */
enum class VoteType { BINARY, SCALE, MULTI_STANCE }

data class BoardVote(
    val role: String,                                     // 投票者角色ID
    val agree: Boolean,                                   // 是否赞成
    val reason: String = "",                              // 投票理由
    val round: Int,                                       // 投票轮次
    val numericScore: Int? = null,    // SCALE 模式 (1-10)
    val stanceLabel: String? = null,  // MULTI_STANCE 模式
)

/** Agent 发言贡献度评分 */
data class ContributionScore(
    val roleId: String,               // 角色ID
    val informationGain: Int = 0,  // 信息增量 1-5
    val logicQuality: Int = 0,     // 论证逻辑 1-5
    val interactionQuality: Int = 0, // 互动质量 1-5
    val brief: String = "",         // 一句话点评
) {
    val overall: Float get() = (informationGain + logicQuality + interactionQuality) / 3f
}

enum class BoardPhase {
    IDLE, ANALYSIS, DEBATE, VOTE, RATING, EXECUTION, DONE,
}

// ── UnifiedResponse ────────────────────────────────────────

data class UnifiedResponse(
    val wantsToSpeak: Boolean,            // Agent是否想发言
    val content: String,                  // 发言正文
    val voteBull: Boolean?,               // 投票方向（true=赞成/null=未投票）
    val rawTags: List<String>,            // 原始标签列表
    val normalizedTags: List<MsgTag>,     // 标准化后的标签列表
    /** Agent 的推理过程（思考链路） */
    val reasoning: String = "",
    /** SCALE 模式的数字评分 (1-10) */
    val numericScore: Int? = null,
    /** MULTI_STANCE 模式的立场标签 */
    val stanceLabel: String? = null,
) {
    companion object {
        fun parse(raw: String, canVote: Boolean, voteType: VoteType = VoteType.BINARY): UnifiedResponse {
            val lines = raw.trim().lines()
            var speak: Boolean? = null  // null = 未显式声明，由兜底逻辑决定
            var voteBull: Boolean? = null
            var numericScore: Int? = null
            var stanceLabel: String? = null
            val rawTags = mutableListOf<String>()
            val contentLines = mutableListOf<String>()
            val reasoningLines = mutableListOf<String>()
            var currentSection = "" // tracks which section we're in
            var hasAnySection = false // 是否匹配到任何结构化标记

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("SPEAK:", ignoreCase = true) ||
                    trimmed.startsWith("SPEAK：", ignoreCase = true) -> {
                        currentSection = "speak"
                        hasAnySection = true
                        val value = trimmed.substringAfter(":").substringAfter("：").trim().uppercase()
                        speak = value.startsWith("YES") || value.startsWith("Y")
                                || value.startsWith("是") || value.startsWith("发言")
                    }
                    trimmed.startsWith("REASONING:", ignoreCase = true) ||
                    trimmed.startsWith("REASONING：", ignoreCase = true) -> {
                        currentSection = "reasoning"
                        hasAnySection = true
                        val firstLine = trimmed.substringAfter(":").substringAfter("：").trim()
                        if (firstLine.isNotBlank()) reasoningLines.add(firstLine)
                    }
                    trimmed.startsWith("CONTENT:", ignoreCase = true) ||
                    trimmed.startsWith("CONTENT：", ignoreCase = true) -> {
                        currentSection = "content"
                        hasAnySection = true
                        val firstLine = trimmed.substringAfter(":").substringAfter("：").trim()
                        if (firstLine.isNotBlank()) contentLines.add(firstLine)
                    }
                    trimmed.startsWith("VOTE:", ignoreCase = true) ||
                    trimmed.startsWith("VOTE：", ignoreCase = true) -> {
                        currentSection = "vote"
                        hasAnySection = true
                        if (canVote) {
                            val value = trimmed.substringAfter(":").substringAfter("：").trim()
                            val upper = value.uppercase()
                            when (voteType) {
                                VoteType.BINARY -> {
                                    voteBull = upper.contains("BULL") || upper.startsWith("A")
                                            || upper.contains("看多") || upper.contains("AGREE")
                                            || upper.contains("同意") || upper.contains("赞成")
                                            || upper.contains("支持") || upper.contains("YES")
                                    // 明确反对
                                    if (!voteBull!!) {
                                        val isExplicitDisagree = upper.contains("BEAR") || upper.contains("DISAGREE")
                                                || upper.contains("反对") || upper.contains("看空")
                                                || upper.contains("NO") || upper.contains("REJECT")
                                                || upper.startsWith("D") || upper.startsWith("N")
                                        if (isExplicitDisagree) voteBull = false
                                        else voteBull = null // 无法判断，不投票
                                    }
                                }
                                VoteType.SCALE -> {
                                    // Parse "VOTE: 7", "VOTE: 7/10", "VOTE:7分", "VOTE: 7 分"
                                    val score = value.replace(Regex("[/／]10"), "").trim()
                                        .replace("分", "").trim()
                                        .filter { it.isDigit() }.take(2).toIntOrNull()
                                    numericScore = score?.coerceIn(1, 10)
                                    voteBull = (numericScore ?: 5) >= 6
                                }
                                VoteType.MULTI_STANCE -> {
                                    // 去掉引号和多余空格
                                    stanceLabel = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
                                    val upper2 = stanceLabel!!.uppercase()
                                    voteBull = upper2.startsWith("A") || upper2.contains("AGREE")
                                            || upper2.contains("WIN") || upper2.contains("INVEST")
                                            || upper2.contains("COMPLY") || upper2.contains("COMPLIANT")
                                            || upper2.contains("IDENTIFIED") || upper2.contains("BREAKTHROUGH")
                                            || upper2.contains("PROPONENT")
                                            || upper2.contains("通过") || upper2.contains("赞成")
                                            || upper2.contains("同意") || upper2.contains("支持")
                                }
                            }
                        }
                    }
                    trimmed.startsWith("TAGS:", ignoreCase = true) ||
                    trimmed.startsWith("TAGS：", ignoreCase = true) -> {
                        currentSection = "tags"
                        hasAnySection = true
                        val value = trimmed.substringAfter(":").substringAfter("：").trim()
                        rawTags.addAll(value.split(",", "，", " ", "|", "、")
                            .map { it.trim() }.filter { it.isNotBlank() })
                    }
                    trimmed.isNotBlank() -> {
                        when (currentSection) {
                            "content" -> contentLines.add(trimmed)
                            "reasoning" -> reasoningLines.add(trimmed)
                        }
                    }
                }
            }

            var content = contentLines.joinToString("\n")
            val reasoning = reasoningLines.joinToString("\n")

            // ── 兜底：如果没有匹配到任何结构化标记，整段 raw 当 CONTENT ──
            if (!hasAnySection && raw.isNotBlank()) {
                content = raw.trim()
            }
            // ── 兜底2：匹配了 SPEAK 等但 CONTENT 为空，从 raw 中提取可用内容 ──
            if (hasAnySection && content.isBlank() && raw.length > 20) {
                // 去掉已识别的 section 标记行，剩下的当 content
                val fallbackLines = lines.filter { line ->
                    val t = line.trim().uppercase()
                    !t.startsWith("SPEAK") && !t.startsWith("VOTE") &&
                    !t.startsWith("TAGS") && !t.startsWith("REASONING") &&
                    !t.startsWith("CONTENT") && t.isNotBlank()
                }
                if (fallbackLines.isNotEmpty()) {
                    content = fallbackLines.joinToString("\n")
                }
            }

            // ── SPEAK 兜底：未声明时，有内容就视为想发言 ──
            val wantsToSpeak = speak ?: content.isNotBlank()

            val normalized = if (rawTags.isNotEmpty()) {
                MsgTag.normalizeAll(rawTags)
            } else if (content.isNotBlank()) {
                MsgTag.normalizeAll(inferRawTags(content))
            } else {
                listOf(MsgTag.GENERAL)
            }

            return UnifiedResponse(
                wantsToSpeak = wantsToSpeak,
                content = content,
                voteBull = voteBull,
                rawTags = rawTags,
                normalizedTags = normalized,
                reasoning = reasoning,
                numericScore = numericScore,
                stanceLabel = stanceLabel,
            )
        }

        private fun inferRawTags(content: String): List<String> {
            val result = mutableListOf<String>()
            val c = content.uppercase()
            // 投资领域
            if (c.contains("估值") || c.contains("PE") || c.contains("EPS")) result.add("VALUATION")
            if (c.contains("增长") || c.contains("营收") || c.contains("利润")) result.add("GROWTH")
            if (c.contains("看多") || c.contains("利好") || c.contains("买入机会")) result.add("PRO")
            if (c.contains("看空") || c.contains("利空") || c.contains("回避")) result.add("CON")
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
