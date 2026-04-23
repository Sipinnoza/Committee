package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.PresetRole

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  GenericSupervisor -- 通用主席/裁判（由 PresetRole 驱动）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  替代硬编码的 SupervisorAgent，实现 SupervisorCapability 接口。
 *  去除所有投资领域专用术语：
 *   - ratingScale 使用动态列表（而非硬编码 Buy/Sell）
 *   - 摘要使用【正方观点】【反方观点】（而非多头/空头）
 */
class GenericSupervisor(
    private val presetRole: PresetRole,
    private val ratingScale: List<String>,
    private val committeeLabel: String,
) : SupervisorCapability {

    override val role: String = presetRole.id
    override val displayName: String = presetRole.displayName
    override val attentionTags: List<MsgTag> = emptyList()
    override val canVote: Boolean = false

    override fun eligible(board: Blackboard): Boolean = !board.finished

    override fun buildUnifiedPrompt(board: Blackboard): String = ""

    // ── buildFinishPrompt ─────────────────────────────────────

    /**
     * 通用结束判断 prompt。
     * 不包含投资/多空等领域术语。
     */
    override fun buildFinishPrompt(board: Blackboard): String {
        val debateCount = board.messages.count { it.role != role }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val agreeCount = board.votes.values.count { it.agree }
            val disagreeCount = board.votes.size - agreeCount
            "投票：${agreeCount}赞成 / ${disagreeCount}反对"
        } else "尚无投票"

        val recentMsgs = if (board.summary.isNotBlank()) {
            "摘要：${board.summary}"
        } else {
            board.messages.takeLast(4).joinToString("\n") { "[${it.role}] ${it.content.take(80)}" }
        }

        return """你是${committeeLabel}的主持人。判断讨论是否充分，可以进行最终评定。

讨论主题：${board.subject} | 轮次：${board.round} | 发言：${debateCount}条 | $voteSummary

重要：必须同时满足以下条件才能回答YES：
1. 各方参与者都已至少发言1次
2. 正反双方的核心论点都已被充分表达
3. 不存在未回应的重要质疑

$recentMsgs

只回答：YES（讨论充分，可以评定）或 NO（继续讨论）"""
    }

    // ── buildSupervisionPrompt ────────────────────────────────

    /**
     * 通用监督点评 prompt。
     */
    override fun buildSupervisionPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(120)}" }
        val voteSummary = if (board.votes.isNotEmpty()) {
            val agreeCount = board.votes.values.count { it.agree }
            val disagreeCount = board.votes.size - agreeCount
            "\n投票：${agreeCount}赞成 / ${disagreeCount}反对"
        } else ""

        return """你是${committeeLabel}的主持人。讨论主题：${board.subject}
轮次：${board.round}/${board.maxRounds}$voteSummary

${if (board.summary.isNotBlank()) "讨论摘要：${board.summary}\n" else ""}
近期发言：
$recent

简短点评讨论状态和方向。100字以内。"""
    }

    // ── buildRatingPrompt ─────────────────────────────────────

    /**
     * 通用评定 prompt。
     * 使用动态 ratingScale 列表，而非硬编码 Buy/Sell。
     */
    override fun buildRatingPrompt(board: Blackboard): String {
        val history = if (board.summary.isNotBlank()) {
            "讨论摘要：${board.summary}\n\n近期发言：\n" +
                board.messages.takeLast(8).joinToString("\n") {
                    "[${it.role}] R${it.round}: ${it.content.take(150)}"
                }
        } else {
            board.messages.joinToString("\n") {
                "[${it.role}] R${it.round}: ${it.content.take(200)}"
            }
        }
        val votes = board.votes.values.joinToString("\n") {
            "[${it.role}] ${if (it.agree) "赞成" else "反对"}"
        }

        val scaleDisplay = ratingScale.joinToString("/")

        return """你是${committeeLabel}的主持人，给出最终评定结果。
讨论主题：${board.subject} | ${board.round}轮讨论 | ${board.messages.size}条发言

$history

${if (votes.isNotBlank()) "投票：\n$votes" else ""}

格式：【最终评定】$scaleDisplay
然后评定理由，200字以内。"""
    }

    // ── buildSummaryPrompt ────────────────────────────────────

    /**
     * 通用摘要 prompt。
     * 使用【正方观点】【反方观点】【当前分歧】【已达成共识】，
     * 而非多头/空头等投资领域术语。
     */
    override fun buildSummaryPrompt(board: Blackboard): String {
        val allMessages = board.messages.joinToString("\n") {
            "[${it.role}] R${it.round}: ${it.content.take(120)}"
        }
        val existingSummary = if (board.summary.isNotBlank()) "\n\n前次摘要：${board.summary}" else ""

        return """总结当前讨论。

讨论主题：${board.subject}
轮次：${board.round}
$existingSummary

全部发言：
$allMessages

请输出结构化摘要：
【正方观点】（核心论点，每条一句话）
【反方观点】（核心论点，每条一句话）
【当前分歧】（未解决的关键分歧）
【已达成共识】（各方一致的观点）
200字以内。"""
    }
}
