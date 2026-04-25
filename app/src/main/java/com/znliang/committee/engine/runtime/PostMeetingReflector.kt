package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.data.db.DecisionActionEntity
import com.znliang.committee.data.db.MeetingOutcomeEntity
import org.json.JSONArray

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
                                    ctx.updateBoard { b -> b.copy(
                                        evolutionNotification = "${evolver.displayName()} Prompt 已自动进化"
                                    ) }
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

    /**
     * 自动提取行动项：用 LLM 分析会议内容，提取 3-7 条 action items 写入 DB
     */
    suspend fun extractActionItems() {
        val board = ctx.boardValue
        if (board.messages.isEmpty()) return

        val lastSpeeches = ctx.speechesValue.takeLast(5).joinToString("\n") {
            "[${it.agent}] ${it.content.take(300)}"
        }

        val prompt = """你是一个会议行动项提取助手。根据以下会议信息，提取 3-7 条可执行的行动项。

## 标的/主题
${board.subject}

## 最终评级
${board.finalRating ?: "无"}

## 讨论摘要
${board.summary.take(500)}

## 最近发言
$lastSpeeches

## 输出格式
输出一个 JSON 数组，每个元素包含 title, description, assignee 三个字段。
assignee 应根据讨论内容推断最合适的负责角色。
只输出 JSON 数组，不要包含其他内容。

示例:
[{"title":"完成风险评估报告","description":"基于讨论中提到的三个风险点，编写详细评估报告","assignee":"Risk Officer"}]"""

        try {
            val raw = ctx.systemLlm.quickCall(prompt)
            val jsonStr = raw.trim().let { s ->
                // Extract JSON array from response, handling markdown code blocks
                val start = s.indexOf('[')
                val end = s.lastIndexOf(']')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }

            val arr = JSONArray(jsonStr)
            val actions = mutableListOf<DecisionActionEntity>()
            for (i in 0 until arr.length().coerceAtMost(7)) {
                val obj = arr.getJSONObject(i)
                actions.add(DecisionActionEntity(
                    traceId = ctx.currentTraceId,
                    subject = board.subject,
                    title = obj.optString("title", "").take(200),
                    description = obj.optString("description", "").take(500),
                    assignee = obj.optString("assignee", ""),
                ))
            }
            if (actions.isNotEmpty()) {
                ctx.actionRepo.insertAll(actions)
                ctx.log("[ActionItems] 自动提取 ${actions.size} 条行动项")
            }
        } catch (e: Exception) {
            Log.w("PostMeetingReflector", "extractActionItems JSON parse failed: ${e.message}")
            ctx.log("[ActionItems] 提取失败: ${e.message}")
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
