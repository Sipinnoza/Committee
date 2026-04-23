package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.PresetRole

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  GenericAgent -- 通用可配置 Agent（由 PresetRole 驱动）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  替代 5 个硬编码 Agent（AnalystAgent / RiskAgent / StrategistAgent
 *  / IntelAgent / ExecutorAgent），通过 PresetRole 配置任意角色的 Agent。
 *
 *  实现 EvolvableAgent 接口，天然支持：
 *   - 经验回忆 / recallRelevantExperience
 *   - Prompt 注入 / enrichPrompt
 */
class GenericAgent(
    private val presetRole: PresetRole,
    private val systemPrompt: String,
    val canUseTools: Boolean = false,
    private val scoringBonus: Double = 0.0,
) : EvolvableAgent {

    override val role: String = presetRole.id
    override val displayName: String = presetRole.displayName

    /** 通用 Agent 默认关注 GENERAL 标签 */
    override val attentionTags: List<MsgTag> = listOf(MsgTag.GENERAL)

    override val canVote: Boolean = true

    // ── eligible ──────────────────────────────────────────────

    /**
     * 标准 "spoken < 2 this round" 规则。
     * canUseTools 的 Agent 允许最多 5 次（情报类角色需要多次补充）。
     */
    override fun eligible(board: Blackboard): Boolean {
        if (board.finished) return false
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        val limit = if (canUseTools) 5 else 2
        return spokenThisRound < limit
    }

    // ── scoring ───────────────────────────────────────────────

    /**
     * 通用评分，适用于 ANY 角色。
     *
     * 评分维度：
     *   1. 距上次发言的轮数（capped at 5） * 1.0
     *   2. 近期消息与 attentionTags 匹配度 * 2.0
     *   3. 投票分歧时 → 应用 scoringBonus
     *   4. 本轮已发言 → -3.0
     *   5. 第一轮 + canUseTools → +3.0 优先发言
     */
    override fun scoring(board: Blackboard): Double {
        var score = 0.0

        // ① 距上次发言的轮数
        val lastSpokeRound = board.messages.lastOrNull { it.role == role }?.round ?: 0
        val roundsSinceLastSpoke = board.round - lastSpokeRound
        score += minOf(roundsSinceLastSpoke, 5) * 1.0

        // ② 近期消息与 attentionTags 的匹配度
        val recentMessages = board.messages.takeLast(4)
        val relevantCount = recentMessages.count { msg ->
            msg.normalizedTags.any { it in attentionTags }
        }
        score += relevantCount * 2.0

        // ③ 投票分歧 → 应用 scoringBonus
        val bullCount = board.votes.values.count { it.agree }
        val bearCount = board.votes.size - bullCount
        val hasDivergence = board.votes.size >= 2 &&
            kotlin.math.abs(bullCount - bearCount) <= 1
        if (hasDivergence) {
            score += scoringBonus
        }

        // ④ 本轮已发言 → 降分
        val spokenThisRound = board.messages.count { it.role == role && it.round == board.round }
        score -= spokenThisRound * 3.0

        // ⑤ 第一轮 + 工具型 Agent → 优先发言
        if (board.round == 1 && canUseTools) {
            score += 3.0
        }

        return score
    }

    // ── buildUnifiedPrompt ────────────────────────────────────

    /**
     * 从 PresetRole 字段动态构建 prompt。
     *
     * 结构：
     *   - 角色身份："你是【{displayName}｜{stance}视角｜超级智能体】"
     *   - 讨论主题
     *   - 上下文 board.contextForAgent
     *   - 动态指令（首次发言 / 已有讨论 / 继续辩论）
     *   - 自我进化指引（通用，不绑定投资领域）
     *   - 输出格式: SPEAK/CONTENT/VOTE/TAGS
     */
    override fun buildUnifiedPrompt(board: Blackboard): String {
        val context = board.contextForAgent(this)

        // 动态指令：根据讨论阶段生成
        val myMessages = board.messages.filter { it.role == role }
        val isFirstSpeech = myMessages.isEmpty()
        val hasOtherSpeakers = board.messages.any { it.role != role && it.role != "supervisor" }

        val instruction = when {
            isFirstSpeech && board.messages.isEmpty() -> {
                // 全场首发
                "这是讨论的开始，你必须发言。请从你的${presetRole.stance}视角出发，直接给出你的核心观点。"
            }
            isFirstSpeech && hasOtherSpeakers -> {
                // 已有其他人发言，本角色首次
                "已有其他参与者发言，请根据已有讨论，从你的${presetRole.stance}视角给出分析和观点。如果有与你立场相反的论点，请回应。"
            }
            hasOtherSpeakers -> {
                // 继续辩论
                "讨论正在进行中。请根据最新发言判断是否需要补充观点、回应质疑或提供新的论据。如果你的观点已充分表达且无新信息，可以选择不发言。"
            }
            else -> {
                "请判断是否需要发言。"
            }
        }

        return """你是【${presetRole.displayName}｜${presetRole.stance}视角｜超级智能体】。讨论主题：${board.subject}
轮次：${board.round}/${board.maxRounds}

$context

$instruction

━━━━━━━━━━━━━━━━━━━━━━━━
角色职责
━━━━━━━━━━━━━━━━━━━━━━━━
${presetRole.responsibility}

━━━━━━━━━━━━━━━━━━━━━━━━
自我进化指引
━━━━━━━━━━━━━━━━━━━━━━━━
你是具备自我进化能力的超级智能体。你的核心使命是：
1. 从你的专业视角提供高质量的分析和判断
2. 主动识别并回应其他参与者的薄弱论据
3. 从历史讨论中学习哪些论证模式最有效
4. 持续优化你的分析框架和表达方式

如果下面的【历史经验记忆】中有相关经验，请参考但不盲从。

输出格式（严格遵守）：
SPEAK: YES 或 NO
CONTENT: （如果SPEAK为YES）你的分析和观点，200字以内
VOTE: BULL 或 BEAR
TAGS: 用逗号分隔（如 BULL,RISK,STRATEGY）"""
    }
}
