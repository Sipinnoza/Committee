package com.znliang.committee.engine.report

import android.content.Context
import com.znliang.committee.R
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
        context: Context,
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
        appendLine("# ${context.getString(R.string.report_title)}")
        appendLine()

        // ── 元信息 ──
        appendLine("## ${context.getString(R.string.report_meeting_info)}")
        appendLine()
        appendLine("| ${context.getString(R.string.report_item)} | ${context.getString(R.string.report_detail)} |")
        appendLine("|------|--------|")
        appendLine("| ${context.getString(R.string.report_subject)} | **$subject** |")
        appendLine("| ${context.getString(R.string.report_committee)} | $committeeLabel |")
        appendLine("| ${context.getString(R.string.report_mode)} | $presetName |")
        appendLine("| ${context.getString(R.string.report_start)} | $startFormatted |")
        appendLine("| ${context.getString(R.string.report_end)} | $endTime |")
        appendLine("| ${context.getString(R.string.report_rounds)} | $totalRounds |")
        appendLine("| ${context.getString(R.string.report_participants)} | ${roles.joinToString(", ")} |")
        if (consensus) {
            val consensusLabel = context.getString(R.string.report_consensus_reached)
            appendLine("| ${context.getString(R.string.home_consensus)} | $consensusLabel |")
        }
        appendLine()

        // ── 最终决策 ──
        if (rating != null) {
            appendLine("## ${context.getString(R.string.report_final_decision)}")
            appendLine()
            appendLine("> **$rating**")
            appendLine()
        }

        // ── 投票结果 ──
        if (votes.isNotEmpty()) {
            appendLine("## ${context.getString(R.string.report_voting_results)}")
            appendLine()
            val agreeCount = votes.values.count { it.agree }
            val disagreeCount = votes.size - agreeCount
            appendLine("- ${context.getString(R.string.report_agree_disagree, agreeCount, disagreeCount, votes.size)}")
            appendLine()
            appendLine("| ${context.getString(R.string.report_role_col)} | ${context.getString(R.string.report_vote_col)} | ${context.getString(R.string.report_reason_col)} |")
            appendLine("|------|------|--------|")
            for ((_, vote) in votes) {
                val voteLabel = if (vote.agree) context.getString(R.string.report_vote_agree) else context.getString(R.string.report_vote_disagree)
                val reason = vote.reason.take(100).ifBlank { "-" }
                appendLine("| ${vote.role} | $voteLabel | $reason |")
            }
            appendLine()
        }

        // ── 讨论摘要 ──
        if (summary.isNotBlank()) {
            appendLine("## ${context.getString(R.string.report_discussion_summary)}")
            appendLine()
            appendLine(summary)
            appendLine()
        }

        // ── 各方观点 ──
        if (speeches.isNotEmpty()) {
            appendLine("## ${context.getString(R.string.report_key_arguments)}")
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
                appendLine("### ${context.getString(R.string.report_human_input)}")
                appendLine()
                for (speech in humanSpeeches) {
                    appendLine("- **R${speech.round}**: ${speech.content.take(300)}")
                }
                appendLine()
            }
        }

        // ── 完整讨论记录 ──
        if (speeches.isNotEmpty()) {
            appendLine("## ${context.getString(R.string.report_full_log)}")
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
        appendLine("*${context.getString(R.string.report_generated_by)}*")
    }

    /**
     * 生成纯文本分享摘要（用于快速分享，不含完整记录）
     */
    fun generateShareText(
        context: Context,
        subject: String,
        committeeLabel: String,
        rating: String?,
        summary: String,
        votes: Map<String, BoardVote>,
        consensus: Boolean,
    ): String = buildString {
        appendLine(context.getString(R.string.report_share_title, committeeLabel))
        appendLine()
        appendLine(context.getString(R.string.report_share_subject, subject))
        if (rating != null) {
            appendLine(context.getString(R.string.report_share_decision, rating))
        }
        if (votes.isNotEmpty()) {
            val agreeCount = votes.values.count { it.agree }
            appendLine(context.getString(R.string.report_share_vote, agreeCount, votes.size))
        }
        if (consensus) {
            appendLine(context.getString(R.string.report_share_consensus))
        }
        if (summary.isNotBlank()) {
            appendLine()
            appendLine(context.getString(R.string.report_share_summary))
            appendLine(summary.take(500))
        }
        appendLine()
        appendLine(context.getString(R.string.report_share_footer))
    }
}
