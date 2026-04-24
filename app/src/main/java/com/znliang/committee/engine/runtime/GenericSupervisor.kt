package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.PresetRole

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  GenericSupervisor -- 通用主席/裁判（由 PresetRole + Mandate 驱动）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  支持动态摘要模板（binary / multi_dimension / convergent）
 *  和多种输出类型（rating / decision / score / open）。
 */
class GenericSupervisor(
    private val presetRole: PresetRole,
    private val ratingScale: List<String>,
    private val committeeLabel: String,
    private val preset: MeetingPreset? = null,
) : SupervisorCapability {

    override val role: String = presetRole.id
    override val displayName: String = presetRole.displayName
    override val attentionTags: List<MsgTag> = emptyList()
    override val canVote: Boolean = false

    private val summaryTemplate: String
        get() = preset?.mandateStr("summary_template", "binary") ?: "binary"

    private val outputType: String
        get() = preset?.mandateStr("output_type", "rating") ?: "rating"

    private val roleNames: List<String>
        get() = preset?.roles
            ?.filter { !it.isSupervisor }
            ?.map { it.displayName }
            ?: emptyList()

    override fun eligible(board: Blackboard): Boolean = !board.finished

    override fun buildUnifiedPrompt(board: Blackboard): String = ""

    // ── buildFinishPrompt ─────────────────────────────────────

    override fun buildFinishPrompt(board: Blackboard): String {
        val debateCount = board.messages.count { it.role != role }
        val voteSummary = buildVoteSummary(board)

        val allParticipants = board.messages
            .map { it.role }
            .filter { it != role && it != "supervisor" && it != "human" }
            .distinct()
        val speakerStats = allParticipants.joinToString("\n") { participant ->
            val count = board.messages.count { it.role == participant }
            "  - $participant: ${count}次发言"
        }
        val totalParticipantCount = allParticipants.size
        val spokenParticipantCount = allParticipants.count { p ->
            board.messages.any { it.role == p }
        }

        val tagsByParticipant = allParticipants.associateWith { p ->
            board.messages.filter { it.role == p }.flatMap { it.normalizedTags }.toSet()
        }
        val hasCrossResponse = tagsByParticipant.entries.any { (p1, tags1) ->
            tagsByParticipant.entries.any { (p2, tags2) ->
                p1 != p2 && tags1.intersect(tags2).isNotEmpty()
            }
        }

        val recentMsgs = if (board.summary.isNotBlank()) {
            "摘要：${board.summary}"
        } else {
            board.messages.takeLast(4).joinToString("\n") { "[${it.role}] ${it.content.take(80)}" }
        }

        // Adapt finish criteria to non-binary modes
        val criterion3 = when (summaryTemplate) {
            "multi_dimension" -> "3. 各维度的评估都已经被充分表达"
            "convergent" -> "3. 核心议题都已被充分讨论，且有明确的收敛方向"
            else -> "3. 正反双方的核心论点都已被充分表达"
        }
        val criterion4 = when (summaryTemplate) {
            "multi_dimension" -> "4. 不同维度之间有交叉回应和关联（当前：${if (hasCrossResponse) "已有交叉回应" else "暂无交叉回应"}）"
            "convergent" -> "4. 参与者之间有互动和回应（当前：${if (hasCrossResponse) "已有交叉回应" else "暂无交叉回应"}）"
            else -> "4. 存在正反双方的交叉回应，而不是各说各话（当前：${if (hasCrossResponse) "已有交叉回应" else "暂无交叉回应"}）"
        }

        return """你是${committeeLabel}的主持人。判断讨论是否充分，可以进行最终评定。

讨论主题：${board.subject} | 轮次：${board.round} | 发言：${debateCount}条 | $voteSummary

参与者发言统计（共${totalParticipantCount}人，已发言${spokenParticipantCount}人）：
$speakerStats
交叉回应：${if (hasCrossResponse) "有" else "无"}

重要：必须同时满足以下所有条件才能回答YES：
1. 至少有${maxOf(totalParticipantCount - 1, 2)}个不同参与者发言过（当前：${spokenParticipantCount}人）
2. 讨论轮次至少达到参与者数量（当前：第${board.round}轮 / 需要至少${totalParticipantCount}轮）
$criterion3
$criterion4
5. 不存在未回应的重要质疑

$recentMsgs

只回答：YES（讨论充分，可以评定）或 NO（继续讨论）"""
    }

    // ── buildSupervisionPrompt ────────────────────────────────

    override fun buildSupervisionPrompt(board: Blackboard): String {
        val recent = board.messages.takeLast(6).joinToString("\n") { "[${it.role}] ${it.content.take(120)}" }
        val voteSummary = if (board.votes.isNotEmpty()) "\n${buildVoteSummary(board)}" else ""

        return """你是${committeeLabel}的主持人。讨论主题：${board.subject}
轮次：${board.round}/${board.maxRounds}$voteSummary

${if (board.summary.isNotBlank()) "讨论摘要：${board.summary}\n" else ""}
近期发言：
$recent

简短点评讨论状态和方向。100字以内。"""
    }

    // ── buildRatingPrompt ─────────────────────────────────────

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
        val votes = buildDetailedVotes(board)
        val scaleDisplay = ratingScale.joinToString("/")

        val outputInstruction = when (outputType) {
            "decision" -> """格式：
【最终决定】$scaleDisplay
【关键理由】（2-3条）
【行动项】（如果有）
200字以内。"""
            "score" -> """格式：
【综合评分】$scaleDisplay
【各维度评分】（列出每个维度及评分）
【评分理由】
200字以内。"""
            "open" -> """请给出自由形式的总结和结论。
如果适用，可参考评级量表：$scaleDisplay
200字以内。"""
            else -> """格式：【最终评定】$scaleDisplay
然后评定理由，200字以内。"""
        }

        return """你是${committeeLabel}的主持人，给出最终评定结果。
讨论主题：${board.subject} | ${board.round}轮讨论 | ${board.messages.size}条发言

$history

${if (votes.isNotBlank()) "投票：\n$votes" else ""}

$outputInstruction"""
    }

    // ── buildSummaryPrompt ────────────────────────────────────

    override fun buildSummaryPrompt(board: Blackboard): String {
        val allMessages = board.messages.joinToString("\n") {
            "[${it.role}] R${it.round}: ${it.content.take(120)}"
        }
        val existingSummary = if (board.summary.isNotBlank()) "\n\n前次摘要：${board.summary}" else ""

        val structurePrompt = when (summaryTemplate) {
            "multi_dimension" -> {
                val dimensions = roleNames.joinToString("\n") { "【${it}维度】（该角色的核心评估和发现）" }
                """请输出结构化摘要：
$dimensions
【综合评估】（跨维度的整体判断）
【关键风险】（需要特别关注的问题）
200字以内。"""
            }
            "convergent" -> """请输出结构化摘要：
【已达成共识】（各方一致的观点）
【仍有分歧】（尚未解决的关键分歧）
【待解决问题】（需要进一步调查或讨论的问题）
【建议下一步】（推荐的后续行动）
200字以内。"""
            else -> """请输出结构化摘要：
【正方观点】（核心论点，每条一句话）
【反方观点】（核心论点，每条一句话）
【当前分歧】（未解决的关键分歧）
【已达成共识】（各方一致的观点）
200字以内。"""
        }

        return """总结当前讨论。

讨论主题：${board.subject}
轮次：${board.round}
$existingSummary

全部发言：
$allMessages

$structurePrompt"""
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun buildVoteSummary(board: Blackboard): String {
        if (board.votes.isEmpty()) return "尚无投票"
        val voteType = preset?.mandateStr("vote_type", "binary")?.uppercase()
        return when (voteType) {
            "SCALE" -> {
                val scores = board.votes.values.mapNotNull { it.numericScore }
                "投票：${scores.size}人评分，平均 ${"%.1f".format(scores.average())}/10"
            }
            "MULTI_STANCE" -> {
                val stances = board.votes.values.mapNotNull { it.stanceLabel }
                val grouped = stances.groupingBy { it }.eachCount()
                grouped.entries.joinToString(" / ") { "${it.key}:${it.value}票" }
            }
            else -> {
                val agreeCount = board.votes.values.count { it.agree }
                val disagreeCount = board.votes.size - agreeCount
                "投票：${agreeCount}赞成 / ${disagreeCount}反对"
            }
        }
    }

    private fun buildDetailedVotes(board: Blackboard): String {
        val voteType = preset?.mandateStr("vote_type", "binary")?.uppercase()
        return board.votes.values.joinToString("\n") { vote ->
            when (voteType) {
                "SCALE" -> "[${vote.role}] 评分: ${vote.numericScore ?: "N/A"}/10"
                "MULTI_STANCE" -> "[${vote.role}] 立场: ${vote.stanceLabel ?: "N/A"}"
                else -> "[${vote.role}] ${if (vote.agree) "赞成" else "反对"}"
            }
        }
    }
}
