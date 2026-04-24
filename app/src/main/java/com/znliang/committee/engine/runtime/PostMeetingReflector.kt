package com.znliang.committee.engine.runtime

import com.znliang.committee.data.db.MeetingOutcomeEntity

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  PostMeetingReflector — 会后反思 + 进化闭环
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  从 AgentRuntime 中分离的协作者，负责：
 *    - 会后自我反思 (reflectOnMeeting)
 *    - 通用反思生成 (generateReflection)
 *    - 反思结果解析 (parseReflection)
 *    - 建议管理 (clearSuggestion / clearAllSuggestions)
 */
class PostMeetingReflector(private val ctx: RuntimeContext) {

    /** 解析反思 LLM 输出 */
    data class ParsedReflection(
        val reflection: String,
        val suggestion: String,
        val priority: String,
    )

    fun parseReflection(raw: String): ParsedReflection {
        var reflection = ""
        var suggestion = ""
        var priority = "MEDIUM"
        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("REFLECTION:", ignoreCase = true) ->
                    reflection = t.substringAfter(":").trim()
                t.startsWith("SUGGESTION:", ignoreCase = true) ->
                    suggestion = t.substringAfter(":").trim()
                t.startsWith("PRIORITY:", ignoreCase = true) -> {
                    val v = t.substringAfter(":").trim().uppercase()
                    if (v in listOf("HIGH", "MEDIUM", "LOW")) priority = v
                }
            }
        }
        return ParsedReflection(
            reflection = reflection.ifBlank { raw.take(200) },
            suggestion = suggestion,
            priority = priority,
        )
    }

    suspend fun generateReflection(
        agent: Agent,
        board: Blackboard,
        myMessages: List<BoardMessage>,
    ): String {
        val currentPrompt = ctx.agentPool.getSystemPromptTextByRoleId(agent.role)
                ?: return ""

        val mySpeeches = myMessages.joinToString("\n") { msg ->
            "[第${msg.round}轮] ${msg.content.take(300)}"
        }

        val outcome = buildString {
            append("Final Rating: ${board.finalRating ?: "None"}\n")
            append("Consensus: ${if (board.consensus) "Yes" else "No"}\n")
            append("Total Rounds: ${board.round}\n")
            append("Total Speeches: ${board.messages.size}\n")
        }

        val reflectPrompt = """你是一个AI Agent的自我反思系统。

## 当前 System Prompt
$currentPrompt

## 标的
${board.subject}

## 我在会议中的发言（${agent.displayName}）
$mySpeeches

## 会议结果
$outcome

## 任务
请反思你在这个会议中的表现，分析你的 system prompt 是否需要改进。

输出格式（严格遵守）：
REFLECTION: （2-3句话总结你在会议中的表现优劣）
SUGGESTION: （具体的 prompt 改进建议，直接给出改进后的完整 prompt 关键段落，150字以内）
PRIORITY: HIGH / MEDIUM / LOW

注意：
- 只建议真正能改善决策质量的修改
- 不要建议增加角色定位，只改进分析框架和输出质量
- 如果当前 prompt 已经很好，SUGGESTION 写 "无需修改" """

        return ctx.systemLlm.quickCall(reflectPrompt)
    }

    /**
     * v7 完整进化闭环：
     *   1. 反思：每个 Agent 自评表现
     *   2. 学习：将经验持久化到 agent_evolution
     *   3. 追踪：记录会议结果到 meeting_outcomes
     *   4. 自动进化：高优先级建议累积后触发 PromptEvolver
     *   5. 技能提炼：从经验中提炼可复用策略
     */
    suspend fun reflectOnMeeting() {
        val board = ctx.boardValue
        if (board.messages.isEmpty()) return

        val participatedRoles = board.messages.map { it.role }.distinct()
        ctx.log("[Reflect] 参与者: $participatedRoles")

        val suggestions = mutableMapOf<String, String>()

        for (roleId in participatedRoles) {
            try {
                val agent = ctx.agents.find { it.role == roleId } ?: continue
                val evolver = ctx.evolverRegistry[roleId]

                if (evolver != null) {
                    // ── 超级智能体反思路径：每个Agent走专属Evolver ──
                    try {
                        val reflection = evolver.reflect(board, ctx.currentTraceId)
                        if (reflection.isEmpty()) {
                            ctx.log("[Reflect] ${evolver.displayName()}: 无反思结果")
                            continue
                        }

                        ctx.log("[Reflect] ${evolver.displayName()}: ${reflection.summary.take(80)}")

                        // 记录建议
                        if (reflection.suggestion.isNotBlank()) {
                            suggestions[roleId] = buildString {
                                append("${reflection.summary}\n")
                                append("建议: ${reflection.suggestion}\n")
                                append("优先级: ${reflection.priority}")
                            }
                        }

                        // ── 追踪：记录会议结果 ──
                        val agentVote = board.votes[roleId]
                        val ratingPositive = ctx.preset.ratingScale.indexOf(board.finalRating ?: "").let { it in 0..1 }
                        ctx.evolutionRepo.saveOutcome(MeetingOutcomeEntity(
                            meetingTraceId = ctx.currentTraceId,
                            agentRole = roleId,
                            subject = board.subject,
                            finalRating = board.finalRating,
                            agentVote = if (agentVote != null) { if (agentVote.agree) "AGREE" else "DISAGREE" } else null,
                            voteCorrect = if (agentVote != null) {
                                (agentVote.agree && ratingPositive) || (!agentVote.agree && !ratingPositive)
                            } else null,
                            selfScore = 0f,
                            lessonsLearned = reflection.summary.take(200),
                        ))

                        // ── 自动进化：高优先级建议触发 PromptEvolver ──
                        if (reflection.priority == "HIGH" && reflection.suggestion.isNotBlank() && reflection.suggestion != "无需修改") {
                            try {
                                val currentPrompt = ctx.agentPool.getSystemPromptTextByRoleId(roleId)
                                    ?: ctx.preset.findRole(roleId)?.responsibility
                                    ?: "Generic agent"
                                val evolved = ctx.promptEvolver.tryAutoEvolve(
                                    roleId, currentPrompt, reflection.suggestion, ctx.currentTraceId
                                )
                                if (evolved) {
                                    ctx.log("[进化] 🧬 ${evolver.displayName()} Prompt 已自动进化！")
                                }
                            } catch (e: Exception) {
                                ctx.log("[进化] ${evolver.displayName()} 自动进化失败: ${e.message}")
                            }
                        }

                        // ── 技能提炼 ──
                        try {
                            val recentExps = ctx.evolutionRepo.getExperiencesByRole(roleId, 10)
                                .map { AgentExperience.fromEntity(it) }
                            val extracted = ctx.skillLibrary.tryExtractSkill(roleId, recentExps)
                            if (extracted) {
                                ctx.log("[技能] 📌 ${evolver.displayName()} 新策略已提炼")
                            }
                        } catch (e: Exception) {
                            ctx.log("[技能] ${evolver.displayName()} 提炼失败: ${e.message}")
                        }

                        ctx.log("[Reflect] ${evolver.displayName()}: 完整进化闭环完成")
                    } catch (e: Exception) {
                        ctx.log("[Reflect] ${roleId} Evolver反思失败: ${e.message}")
                    }
                } else {
                    // ── 无专属Evolver的Agent走通用反思路径 ──
                    val myMessages = board.messages.filter { it.role == roleId }
                    val reflection = generateReflection(agent, board, myMessages)
                    if (reflection.isBlank()) continue
                    suggestions[roleId] = reflection
                    ctx.log("[Reflect] ${agent.displayName}: 通用反思完成")
                }
            } catch (e: Exception) {
                ctx.log("[Reflect] $roleId 失败: ${e.message}")
            }
        }

        if (suggestions.isNotEmpty()) {
            ctx.setPromptSuggestions(suggestions)
            ctx.log("[Reflect] ${suggestions.size} 条优化建议已就绪")
        }
    }

    /** 清除指定 agent 的优化建议（用户已处理） */
    fun clearSuggestion(roleId: String) {
        ctx.setPromptSuggestions(ctx.promptSuggestionsValue - roleId)
    }

    /** 清除所有建议 */
    fun clearAllSuggestions() {
        ctx.setPromptSuggestions(emptyMap())
    }
}
