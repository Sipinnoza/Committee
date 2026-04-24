package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.domain.model.SpeechRecord
import com.znliang.committee.engine.ErrorType

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  HumanInteraction — Human-in-the-Loop 交互逻辑
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  从 AgentRuntime 中分离的协作者，负责：
 *    - 人类消息/投票注入
 *    - 追问 Agent
 *    - 权重设置 / 决策覆写
 *    - 暂停 / 恢复 / 确认执行
 */
class HumanInteraction(
    private val ctx: RuntimeContext,
    private val orchestrator: MeetingOrchestrator,
) {

    @Volatile private var followUpInProgress = false

    // ── 暂停/恢复 ──────────────────────────────────────────────

    fun pauseMeeting() {
        ctx.setIsPaused(true)
        ctx.log("[Human] Meeting paused")
    }

    fun resumeMeeting() {
        ctx.setIsPaused(false)
        ctx.log("[Human] Meeting resumed")
    }

    // ── 消息注入 ──────────────────────────────────────────────

    fun injectHumanMessage(content: String) {
        if (content.isBlank()) return
        val tags = inferHumanTags(content)
        ctx.addBoardMessage("human", content, tags)
        ctx.addSpeech(SpeechRecord(
            id = "human_${System.currentTimeMillis()}",
            agent = "human",
            content = content,
            summary = "",
            round = ctx.boardValue.round,
        ))
        ctx.log("[Human] Injected message: ${content.take(60)}...")
    }

    // ── 投票 ──────────────────────────────────────────────────

    fun injectHumanVote(agree: Boolean, reason: String) {
        ctx.updateBoard { b -> b.copy(
            votes = b.votes + ("human" to BoardVote(
                role = "human",
                agree = agree,
                reason = reason,
                round = b.round,
            ))
        ) }
        // Check consensus after human vote
        val b = ctx.boardValue
        if (b.hasConsensus(ctx.voteType) && !b.consensus) {
            ctx.updateBoard { it.copy(consensus = true) }
            ctx.log("[Consensus] Agree ${(b.agreeRatio() * 100).toInt()}%")
        }
        ctx.log("[Human] Vote: ${if (agree) "Agree" else "Disagree"} reason=$reason")
    }

    // ── 权重 ──────────────────────────────────────────────────

    /** 用户设置 Agent 话语权重 */
    fun setAgentWeight(roleId: String, weight: Float) {
        val clamped = weight.coerceIn(0.1f, 3.0f)
        ctx.updateBoard { b -> b.copy(
            userWeights = b.userWeights + (roleId to clamped)
        ) }
        ctx.log("[Human] Set weight: $roleId = ${"%.1f".format(clamped)}")
    }

    // ── 决策覆写 ──────────────────────────────────────────────

    /**
     * 用户覆写决策 — 推翻 Agent 结论，记录用户自己的判断
     * 体现「用户是决策主导者，Agent 是辅助工具」
     */
    fun overrideDecision(newRating: String, reason: String) {
        ctx.updateBoard { it.copy(
            userOverrideRating = newRating,
            userOverrideReason = reason,
        ) }
        ctx.log("[Human] Override: $newRating reason=${reason.take(60)}")
    }

    // ── 标签推断 ──────────────────────────────────────────────

    fun inferHumanTags(content: String): List<String> {
        val result = mutableListOf<String>()
        val c = content.uppercase()
        if (c.contains("风险") || c.contains("RISK")) result.add("RISK")
        if (c.contains("看多") || c.contains("赞成") || c.contains("支持")) result.add("PRO")
        if (c.contains("看空") || c.contains("反对") || c.contains("质疑")) result.add("CON")
        if (c.contains("问题") || c.contains("QUESTION")) result.add("QUESTION")
        return result.ifEmpty { listOf("GENERAL") }
    }

    // ── 追问 ──────────────────────────────────────────────────

    /**
     * 用户追问：针对特定 Agent 的发言提出问题
     * Agent 基于当前 blackboard 上下文 + 用户问题 进行回答
     *
     * 暂停主循环以保护 _board/_speeches 写入，防止与 runLoop 竞态
     */
    fun followUpQuestion(roleId: String, question: String) {
        if (question.isBlank()) return
        if (followUpInProgress) {
            ctx.log("[FollowUp] Already in progress, ignoring")
            return
        }
        followUpInProgress = true
        // 注入用户提问
        ctx.addBoardMessage("human", "[@$roleId] $question", listOf("QUESTION"))
        ctx.addSpeech(SpeechRecord(
            id = "human_${System.currentTimeMillis()}",
            agent = "human",
            content = "[@$roleId] $question",
            summary = "",
            round = ctx.boardValue.round,
        ))
        ctx.log("[Human] Follow-up to $roleId: ${question.take(60)}...")

        // 让目标 Agent 回答
        val agent = ctx.agents.find { it.role == roleId } ?: run {
            followUpInProgress = false
            return
        }
        val recordId = "followup_${System.currentTimeMillis()}"
        ctx.addSpeech(SpeechRecord(
            id = recordId,
            agent = roleId,
            content = "",
            summary = "",
            round = ctx.boardValue.round,
            isStreaming = true,
        ))

        // 暂时暂停主循环，防止竞态写入
        val wasPaused = ctx.isPausedValue
        if (!wasPaused) ctx.setIsPaused(true)

        ctx.launchInScope {
            try {
                val board = ctx.boardValue
                val context = board.contextForAgent(agent)
                val prompt = """你是【${agent.displayName}】。用户针对你的发言提出了追问，请直接回答。

讨论主题：${board.subject}
${context}

用户追问：$question

请直接回答，200字以内。不要输出 SPEAK/VOTE/TAGS 等格式标记。"""

                val sb = StringBuilder()
                val outcome = orchestrator.collectWithBatchedEmission(
                    flow = ctx.agentPool.callAgentStreamingByRoleId(
                        roleId,
                        MicContext(
                            traceId = "followup_${System.currentTimeMillis()}",
                            causedBy = "human",
                            round = board.round,
                            phase = MeetingState.PHASE1_DEBATE,
                            subject = board.subject,
                            agentRoleId = roleId,
                            task = "followup",
                        ),
                        systemPromptOverride = prompt,
                    ),
                    sb = sb,
                    recordId = recordId,
                    extractContent = { it },
                )

                val finalContent = sb.toString().trim()
                if (outcome is MeetingOrchestrator.CollectOutcome.StreamError) {
                    // API 错误 — 显示给用户而非当作 agent 回答
                    ctx.removeSpeech(recordId)
                    val errorDisplay = if (outcome.type == ErrorType.BILLING)
                        "⚠️ API 余额不足，无法回答追问。请充值后重试。"
                    else
                        "⚠️ 请求失败，请稍后重试。"
                    ctx.addBoardMessage("supervisor", errorDisplay, emptyList())
                    ctx.log("[FollowUp] API error: ${outcome.type} ${outcome.message}")
                } else {
                    ctx.emitSpeechUpdate(recordId, finalContent, isStreaming = false)
                    ctx.addBoardMessage(roleId, finalContent, listOf("GENERAL"))
                    ctx.log("[FollowUp] $roleId responded: ${finalContent.take(80)}")
                }
            } catch (e: Exception) {
                ctx.removeSpeech(recordId)
                ctx.log("[FollowUp] Error: ${e.message}")
            } finally {
                // 恢复之前的暂停状态
                if (!wasPaused) ctx.setIsPaused(false)
                followUpInProgress = false
            }
        }
    }

    // ── 确认执行 ──────────────────────────────────────────────

    fun confirmExecution() {
        val b = ctx.boardValue
        if (b.phase == BoardPhase.EXECUTION) {
            ctx.updateBoard { it.copy(finished = true, phase = BoardPhase.DONE) }
        }
    }
}
