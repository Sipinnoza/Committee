package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.PresetRole
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GenericAgent — verifies eligibility, scoring, prompt building,
 * voting config, and attention tag inference.
 */
class GenericAgentTest {

    // ── Test fixtures ────────────────────────────────────────────

    private fun makePresetRole(
        id: String = "analyst",
        displayName: String = "Analyst",
        stance: String = "Bull",
        responsibility: String = "Bull Case + Valuation Framework",
        isSupervisor: Boolean = false,
        canUseTools: Boolean = false,
    ) = PresetRole(
        id = id,
        displayName = displayName,
        stance = stance,
        responsibility = responsibility,
        systemPromptKey = "role_$id",
        colorHex = "#4CAF50",
        canUseTools = canUseTools,
        isSupervisor = isSupervisor,
    )

    private fun makeAgent(
        presetRole: PresetRole = makePresetRole(),
        promptStyle: String = "debate",
        scoringBonus: Double = 0.0,
        canUseTools: Boolean = false,
        voteType: VoteType = VoteType.BINARY,
        voteOptions: List<String> = emptyList(),
    ) = GenericAgent(
        presetRole = presetRole,
        systemPrompt = "You are a test agent.",
        canUseTools = canUseTools,
        scoringBonus = scoringBonus,
        promptStyle = promptStyle,
        voteType = voteType,
        voteOptions = voteOptions,
    )

    private fun makeBoard(
        subject: String = "Test Topic",
        round: Int = 1,
        maxRounds: Int = 15,
        messages: List<BoardMessage> = emptyList(),
        votes: Map<String, BoardVote> = emptyMap(),
        finished: Boolean = false,
        summary: String = "",
    ) = Blackboard(
        subject = subject,
        round = round,
        maxRounds = maxRounds,
        messages = messages,
        votes = votes,
        finished = finished,
        summary = summary,
    )

    private fun makeMessage(
        role: String,
        content: String,
        round: Int = 1,
        rawTags: List<String> = emptyList(),
    ) = BoardMessage(
        role = role,
        content = content,
        round = round,
        rawTags = rawTags,
    )

    // ── eligible() tests ─────────────────────────────────────────

    @Test
    fun `eligible returns false when board is finished`() {
        val agent = makeAgent()
        val board = makeBoard(finished = true)
        assertFalse(agent.eligible(board))
    }

    @Test
    fun `eligible returns true when agent has not spoken this round`() {
        val agent = makeAgent()
        val board = makeBoard(round = 1, messages = emptyList())
        assertTrue(agent.eligible(board))
    }

    @Test
    fun `eligible returns true when agent spoke once this round`() {
        val agent = makeAgent()
        val board = makeBoard(
            round = 2,
            messages = listOf(
                makeMessage("analyst", "Some content", round = 2),
            ),
        )
        assertTrue(agent.eligible(board))
    }

    @Test
    fun `eligible returns false when agent spoke twice this round for non-tool agent`() {
        val agent = makeAgent(canUseTools = false)
        val board = makeBoard(
            round = 2,
            messages = listOf(
                makeMessage("analyst", "First speech", round = 2),
                makeMessage("analyst", "Second speech", round = 2),
            ),
        )
        assertFalse(agent.eligible(board))
    }

    @Test
    fun `eligible allows up to 5 speeches for tool-capable agent`() {
        val agent = makeAgent(canUseTools = true)
        val board = makeBoard(
            round = 1,
            messages = listOf(
                makeMessage("analyst", "1st", round = 1),
                makeMessage("analyst", "2nd", round = 1),
                makeMessage("analyst", "3rd", round = 1),
                makeMessage("analyst", "4th", round = 1),
            ),
        )
        assertTrue("Tool agent should be eligible with 4 speeches", agent.eligible(board))

        val boardFull = makeBoard(
            round = 1,
            messages = listOf(
                makeMessage("analyst", "1st", round = 1),
                makeMessage("analyst", "2nd", round = 1),
                makeMessage("analyst", "3rd", round = 1),
                makeMessage("analyst", "4th", round = 1),
                makeMessage("analyst", "5th", round = 1),
            ),
        )
        assertFalse("Tool agent should not be eligible with 5 speeches", agent.eligible(boardFull))
    }

    @Test
    fun `eligible only counts messages for current round`() {
        val agent = makeAgent()
        val board = makeBoard(
            round = 3,
            messages = listOf(
                makeMessage("analyst", "Round 1 speech", round = 1),
                makeMessage("analyst", "Round 2 speech", round = 2),
                makeMessage("analyst", "Round 2 second speech", round = 2),
            ),
        )
        assertTrue("Previous round speeches should not count", agent.eligible(board))
    }

    // ── scoring() tests ──────────────────────────────────────────

    @Test
    fun `scoring gives higher score to agent that hasnt spoken recently`() {
        val agent = makeAgent()
        val recentBoard = makeBoard(
            round = 3,
            messages = listOf(
                makeMessage("analyst", "content", round = 2),
            ),
        )
        val staleBoard = makeBoard(
            round = 6,
            messages = listOf(
                makeMessage("analyst", "content", round = 1),
            ),
        )
        val recentScore = agent.scoring(recentBoard)
        val staleScore = agent.scoring(staleBoard)
        assertTrue(
            "Agent with longer silence should score higher (stale=$staleScore > recent=$recentScore)",
            staleScore > recentScore,
        )
    }

    @Test
    fun `scoring deducts for speaking this round`() {
        val agent = makeAgent()
        val noSpeechBoard = makeBoard(round = 2, messages = emptyList())
        val spokeBoard = makeBoard(
            round = 2,
            messages = listOf(makeMessage("analyst", "content", round = 2)),
        )
        val noSpeechScore = agent.scoring(noSpeechBoard)
        val spokeScore = agent.scoring(spokeBoard)
        assertTrue(
            "Score should be lower after speaking this round (spoke=$spokeScore < noSpeech=$noSpeechScore)",
            spokeScore < noSpeechScore,
        )
    }

    @Test
    fun `scoring applies bonus during divergence`() {
        val agent = makeAgent(scoringBonus = 5.0)
        // Divergent votes: 1 agree, 1 disagree
        val divergentBoard = makeBoard(
            round = 3,
            votes = mapOf(
                "agent1" to BoardVote(role = "agent1", agree = true, round = 2),
                "agent2" to BoardVote(role = "agent2", agree = false, round = 2),
            ),
        )
        val noDivergenceBoard = makeBoard(round = 3)

        val divScore = agent.scoring(divergentBoard)
        val noDivScore = agent.scoring(noDivergenceBoard)
        assertTrue(
            "Divergence should apply scoring bonus (div=$divScore > noDiv=$noDivScore)",
            divScore > noDivScore,
        )
    }

    @Test
    fun `scoring does not apply bonus when no divergence`() {
        val agent = makeAgent(scoringBonus = 5.0)
        // All agree: not a close vote
        val unanimousBoard = makeBoard(
            round = 3,
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 2),
                "a2" to BoardVote(role = "a2", agree = true, round = 2),
                "a3" to BoardVote(role = "a3", agree = true, round = 2),
            ),
        )
        val emptyBoard = makeBoard(round = 3)
        // With 3 agree, 0 disagree: abs(3-0)=3 > 1, so no divergence
        val unanimousScore = agent.scoring(unanimousBoard)
        val emptyScore = agent.scoring(emptyBoard)
        assertEquals(
            "No divergence bonus when votes are unanimous",
            emptyScore, unanimousScore, 0.01,
        )
    }

    @Test
    fun `scoring gives tool agent bonus in round 1`() {
        val toolAgent = makeAgent(canUseTools = true)
        val noToolAgent = makeAgent(canUseTools = false)
        val board = makeBoard(round = 1)
        val toolScore = toolAgent.scoring(board)
        val noToolScore = noToolAgent.scoring(board)
        assertTrue(
            "Tool agent should score higher in round 1 (tool=$toolScore > noTool=$noToolScore)",
            toolScore > noToolScore,
        )
    }

    @Test
    fun `scoring boosts relevance based on attention tag matches`() {
        val riskAgent = makeAgent(presetRole = makePresetRole(stance = "Bear"))
        val board = makeBoard(
            round = 3,
            messages = listOf(
                makeMessage("other", "Risk warning", round = 2, rawTags = listOf("RISK")),
                makeMessage("other", "More risk", round = 2, rawTags = listOf("CON")),
                makeMessage("other", "Bad outlook", round = 2, rawTags = listOf("RISK")),
                makeMessage("other", "Bearish signal", round = 2, rawTags = listOf("CON")),
            ),
        )
        val boardNoMatch = makeBoard(
            round = 3,
            messages = listOf(
                makeMessage("other", "Growth prospects", round = 2, rawTags = listOf("GROWTH")),
                makeMessage("other", "More growth", round = 2, rawTags = listOf("PRO")),
                makeMessage("other", "Bullish signal", round = 2, rawTags = listOf("PRO")),
                makeMessage("other", "Earnings up", round = 2, rawTags = listOf("GROWTH")),
            ),
        )
        val matchScore = riskAgent.scoring(board)
        val noMatchScore = riskAgent.scoring(boardNoMatch)
        assertTrue(
            "Agent should score higher when recent messages match attention tags (match=$matchScore > noMatch=$noMatchScore)",
            matchScore > noMatchScore,
        )
    }

    // ── buildUnifiedPrompt() tests ───────────────────────────────

    @Test
    fun `buildUnifiedPrompt contains subject and round info`() {
        val agent = makeAgent()
        val board = makeBoard(subject = "NVDA Stock Analysis", round = 3, maxRounds = 15)
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Prompt should contain subject", prompt.contains("NVDA Stock Analysis"))
        assertTrue("Prompt should contain round", prompt.contains("3/15"))
    }

    @Test
    fun `buildUnifiedPrompt contains role and stance`() {
        val agent = makeAgent(presetRole = makePresetRole(
            displayName = "Risk Officer",
            stance = "Bear",
            responsibility = "Bear Case + Risk Calendar",
        ))
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Prompt should contain display name", prompt.contains("Risk Officer"))
        assertTrue("Prompt should contain stance", prompt.contains("Bear"))
        assertTrue("Prompt should contain responsibility", prompt.contains("Bear Case + Risk Calendar"))
    }

    @Test
    fun `buildUnifiedPrompt debate style has core judgment section`() {
        val agent = makeAgent(promptStyle = "debate")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Debate prompt should reference core judgment section",
            prompt.contains("核心判断"))
        assertTrue("Debate prompt should reference argument section",
            prompt.contains("论据与数据"))
    }

    @Test
    fun `buildUnifiedPrompt roundtable or review style has evaluation section`() {
        val agent = makeAgent(promptStyle = "review")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Review prompt should have evaluation section",
            prompt.contains("评估结论"))
        assertTrue("Review prompt should have findings section",
            prompt.contains("分析依据"))
    }

    @Test
    fun `buildUnifiedPrompt creative style has proposal section`() {
        val agent = makeAgent(promptStyle = "creative")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Creative prompt should have proposal section",
            prompt.contains("创意提案"))
        assertTrue("Creative prompt should have description section",
            prompt.contains("创意描述"))
    }

    @Test
    fun `buildUnifiedPrompt collaborative style has findings section`() {
        val agent = makeAgent(promptStyle = "collaborative")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Collaborative prompt should have findings section",
            prompt.contains("关键发现"))
        assertTrue("Collaborative prompt should have suggestion section",
            prompt.contains("建议方案"))
    }

    @Test
    fun `buildUnifiedPrompt pitch style has core view section`() {
        val agent = makeAgent(promptStyle = "pitch")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Pitch prompt should have core view section",
            prompt.contains("核心观点"))
        assertTrue("Pitch prompt should have response section",
            prompt.contains("回应与提问"))
    }

    @Test
    fun `buildUnifiedPrompt contains VOTE rule for BINARY`() {
        val agent = makeAgent(voteType = VoteType.BINARY)
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("BINARY vote should mention AGREE/DISAGREE",
            prompt.contains("AGREE") && prompt.contains("DISAGREE"))
    }

    @Test
    fun `buildUnifiedPrompt contains VOTE rule for SCALE`() {
        val agent = makeAgent(voteType = VoteType.SCALE)
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("SCALE vote should mention 1-10",
            prompt.contains("1-10"))
    }

    @Test
    fun `buildUnifiedPrompt contains VOTE rule for MULTI_STANCE`() {
        val options = listOf("Invest", "Conditional Interest", "Pass")
        val agent = makeAgent(voteType = VoteType.MULTI_STANCE, voteOptions = options)
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("MULTI_STANCE vote should list options",
            options.all { prompt.contains(it) })
    }

    @Test
    fun `buildUnifiedPrompt early rounds force SPEAK YES`() {
        val agent = makeAgent(promptStyle = "debate")
        val board = makeBoard(round = 1) // Early round
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Early round should force SPEAK: YES",
            prompt.contains("前3轮必须发言") || prompt.contains("不允许选择NO"))
    }

    @Test
    fun `buildUnifiedPrompt collaborative always forces SPEAK YES`() {
        val agent = makeAgent(promptStyle = "collaborative")
        val board = makeBoard(round = 5) // Not early round
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Collaborative should always force SPEAK: YES",
            prompt.contains("不允许选择NO"))
    }

    @Test
    fun `buildUnifiedPrompt includes prior summary for repeated speech`() {
        val agent = makeAgent()
        val board = makeBoard(
            round = 3,
            messages = listOf(
                makeMessage("analyst", "My earlier analysis shows strong earnings", round = 1),
                makeMessage("other", "I disagree with that", round = 2),
            ),
        )
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Prompt should include history summary header",
            prompt.contains("历史发言摘要"))
        assertTrue("Prompt should include anti-repetition rule",
            prompt.contains("严禁重复"))
    }

    // ── canVote tests ────────────────────────────────────────────

    @Test
    fun `canVote is always true for GenericAgent`() {
        val agent = makeAgent()
        assertTrue("GenericAgent should always be able to vote", agent.canVote)
    }

    @Test
    fun `canVote is true regardless of preset role config`() {
        val supervisorPreset = makePresetRole(isSupervisor = true)
        val agent = makeAgent(presetRole = supervisorPreset)
        assertTrue("GenericAgent canVote is always true (supervisor is a different class)", agent.canVote)
    }

    // ── inferAttentionTags() tests ───────────────────────────────

    @Test
    fun `inferAttentionTags for Bull stance returns PRO and GROWTH`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Bull"))
        assertTrue("Bull should have PRO tag", agent.attentionTags.contains(MsgTag.PRO))
        assertTrue("Bull should have GROWTH tag", agent.attentionTags.contains(MsgTag.GROWTH))
    }

    @Test
    fun `inferAttentionTags for Bear stance returns CON and RISK`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Bear"))
        assertTrue("Bear should have CON tag", agent.attentionTags.contains(MsgTag.CON))
        assertTrue("Bear should have RISK tag", agent.attentionTags.contains(MsgTag.RISK))
    }

    @Test
    fun `inferAttentionTags for Neutral Framework returns STRATEGY`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Neutral / Framework"))
        assertTrue("Neutral/Framework should have STRATEGY tag",
            agent.attentionTags.contains(MsgTag.STRATEGY))
    }

    @Test
    fun `inferAttentionTags for Execution stance returns EXECUTION and STRATEGY`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Execution"))
        assertTrue("Execution should have EXECUTION tag",
            agent.attentionTags.contains(MsgTag.EXECUTION))
        assertTrue("Execution should have STRATEGY tag",
            agent.attentionTags.contains(MsgTag.STRATEGY))
    }

    @Test
    fun `inferAttentionTags for Facts stance returns NEWS`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Facts"))
        assertTrue("Facts should have NEWS tag", agent.attentionTags.contains(MsgTag.NEWS))
    }

    @Test
    fun `inferAttentionTags for UX stance returns DESIGN and QUALITY`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "UX"))
        assertTrue("UX should have DESIGN tag", agent.attentionTags.contains(MsgTag.DESIGN))
        assertTrue("UX should have QUALITY tag", agent.attentionTags.contains(MsgTag.QUALITY))
    }

    @Test
    fun `inferAttentionTags for Security stance returns FEASIBILITY and RISK`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Security"))
        assertTrue("Security should have FEASIBILITY tag",
            agent.attentionTags.contains(MsgTag.FEASIBILITY))
        assertTrue("Security should have RISK tag",
            agent.attentionTags.contains(MsgTag.RISK))
    }

    @Test
    fun `inferAttentionTags for unknown stance returns GENERAL`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "SomethingUnknown"))
        assertTrue("Unknown stance should fallback to GENERAL",
            agent.attentionTags.contains(MsgTag.GENERAL))
    }

    @Test
    fun `inferAttentionTags for chinese stance keywords`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "看多"))
        assertTrue("Chinese bull keyword should produce PRO tag",
            agent.attentionTags.contains(MsgTag.PRO))
    }

    @Test
    fun `inferAttentionTags for Wild Ideas returns PRO and STRATEGY`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Wild Ideas"))
        assertTrue("Wild Ideas should have PRO tag",
            agent.attentionTags.contains(MsgTag.PRO))
        assertTrue("Wild Ideas should have STRATEGY tag",
            agent.attentionTags.contains(MsgTag.STRATEGY))
    }

    @Test
    fun `inferAttentionTags for Scrutiny returns RISK CON QUALITY`() {
        val agent = makeAgent(presetRole = makePresetRole(stance = "Scrutiny"))
        assertTrue("Scrutiny should have RISK tag",
            agent.attentionTags.contains(MsgTag.RISK))
        assertTrue("Scrutiny should have CON tag",
            agent.attentionTags.contains(MsgTag.CON))
        assertTrue("Scrutiny should have QUALITY tag",
            agent.attentionTags.contains(MsgTag.QUALITY))
    }

    // ── buildUnifiedPrompt evolution guide section ────────────────

    @Test
    fun `buildUnifiedPrompt includes evolution guide`() {
        val agent = makeAgent(promptStyle = "debate")
        val board = makeBoard()
        val prompt = agent.buildUnifiedPrompt(board)
        assertTrue("Prompt should contain evolution guide section",
            prompt.contains("自我进化指引"))
        assertTrue("Prompt should reference super intelligence",
            prompt.contains("超级智能体"))
    }
}
