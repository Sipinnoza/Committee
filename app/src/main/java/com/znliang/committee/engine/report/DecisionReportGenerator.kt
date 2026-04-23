package com.znliang.committee.engine.report

import com.znliang.committee.data.db.MeetingSessionEntity
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.runtime.Blackboard
import com.znliang.committee.engine.runtime.BoardVote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 决策报告生成器 — 团队决策的核心交付物
 *
 * 会议结束后生成结构化 Markdown 决策报告，包含：
 * - 会议元信息（主题、时间、参会成员、会议模式）
 * - 讨论摘要
 * - 各方观点汇总
 * - 投票结果
 * - 最终决策评级
 * - 关键论据
 */
object DecisionReportGenerator {

    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    /**
     * 生成完整的 Markdown 决策报告
     */
    fun generateMarkdown(
        subject: String,
        presetName: String,
        committeeLabel: String,
        roles: List<String>,
        speeches: List<SpeechRecord>,
        votes: Map<String, BoardVote>,
        rating: String?,
        summary: String,
        startTime: Long,
        totalRounds: Int,
        consensus: Boolean,
    ): String = buildString {
        val startInstant = Instant.ofEpochMilli(startTime)
        val endTime = dateFormatter.format(Instant.now())
        val startFormatted = dateFormatter.format(startInstant)

        // ── 标题 ──
        appendLine("# Decision Report")
        appendLine()

        // ── 元信息 ──
        appendLine("## Meeting Info")
        appendLine()
        appendLine("| Item | Detail |")
        appendLine("|------|--------|")
        appendLine("| Subject | **$subject** |")
        appendLine("| Committee | $committeeLabel |")
        appendLine("| Mode | $presetName |")
        appendLine("| Start | $startFormatted |")
        appendLine("| End | $endTime |")
        appendLine("| Rounds | $totalRounds |")
        appendLine("| Participants | ${roles.joinToString(", ")} |")
        if (consensus) {
            appendLine("| Consensus | Reached |")
        }
        appendLine()

        // ── 最终决策 ──
        if (rating != null) {
            appendLine("## Final Decision")
            appendLine()
            appendLine("> **$rating**")
            appendLine()
        }

        // ── 投票结果 ──
        if (votes.isNotEmpty()) {
            appendLine("## Voting Results")
            appendLine()
            val agreeCount = votes.values.count { it.agree }
            val disagreeCount = votes.size - agreeCount
            appendLine("- Agree: **$agreeCount** / Disagree: **$disagreeCount** (Total: ${votes.size})")
            appendLine()
            appendLine("| Role | Vote | Reason |")
            appendLine("|------|------|--------|")
            for ((_, vote) in votes) {
                val voteLabel = if (vote.agree) "Agree" else "Disagree"
                val reason = vote.reason.take(100).ifBlank { "-" }
                appendLine("| ${vote.role} | $voteLabel | $reason |")
            }
            appendLine()
        }

        // ── 讨论摘要 ──
        if (summary.isNotBlank()) {
            appendLine("## Discussion Summary")
            appendLine()
            appendLine(summary)
            appendLine()
        }

        // ── 各方观点 ──
        if (speeches.isNotEmpty()) {
            appendLine("## Key Arguments by Role")
            appendLine()

            val byAgent = speeches.groupBy { it.agent }
            for ((agent, agentSpeeches) in byAgent) {
                if (agent == "system" || agent == "human") continue
                appendLine("### $agent")
                appendLine()
                // 取每位 agent 最有代表性的发言（第一条和最后一条）
                val representative = buildList {
                    add(agentSpeeches.first())
                    if (agentSpeeches.size > 1) add(agentSpeeches.last())
                    if (agentSpeeches.size > 3) {
                        add(agentSpeeches[agentSpeeches.size / 2])
                    }
                }.distinctBy { it.id }.sortedBy { it.round }

                for (speech in representative) {
                    val contentPreview = speech.content.take(300)
                    appendLine("- **R${speech.round}**: $contentPreview")
                }
                appendLine()
            }

            // 人类发言单独展示
            val humanSpeeches = speeches.filter { it.agent == "human" }
            if (humanSpeeches.isNotEmpty()) {
                appendLine("### Human Input")
                appendLine()
                for (speech in humanSpeeches) {
                    appendLine("- **R${speech.round}**: ${speech.content.take(300)}")
                }
                appendLine()
            }
        }

        // ── 完整讨论记录 ──
        if (speeches.isNotEmpty()) {
            appendLine("## Full Discussion Log")
            appendLine()
            for (speech in speeches) {
                appendLine("**[R${speech.round}] ${speech.agent}**")
                appendLine()
                appendLine(speech.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }

        // ── 页脚 ──
        appendLine("---")
        appendLine("*Generated by Agentra — Multi-Agent Decision System*")
    }

    /**
     * 生成纯文本分享摘要（用于快速分享，不含完整记录）
     */
    fun generateShareText(
        subject: String,
        committeeLabel: String,
        rating: String?,
        summary: String,
        votes: Map<String, BoardVote>,
        consensus: Boolean,
    ): String = buildString {
        appendLine("[$committeeLabel] Decision Report")
        appendLine()
        appendLine("Subject: $subject")
        if (rating != null) {
            appendLine("Decision: $rating")
        }
        if (votes.isNotEmpty()) {
            val agreeCount = votes.values.count { it.agree }
            appendLine("Vote: $agreeCount/${votes.size} Agree")
        }
        if (consensus) {
            appendLine("Status: Consensus Reached")
        }
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("Summary:")
            appendLine(summary.take(500))
        }
        appendLine()
        appendLine("— Agentra")
    }
}
