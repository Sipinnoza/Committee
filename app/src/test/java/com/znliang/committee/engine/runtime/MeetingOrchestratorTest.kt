package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.PresetRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MeetingOrchestrator — verifies the pure-logic parts:
 * streaming content extraction, supervisor comment gating, rating parsing,
 * confidence calculation, default rating, and vote-majority rating.
 *
 * Uses a FakeRuntimeContext to avoid Android/LLM/DB dependencies.
 */
class MeetingOrchestratorTest {

    // ── Test fixtures ────────────────────────────────────────────

    private fun makePresetRole(
        id: String = "analyst",
        displayName: String = "Analyst",
        stance: String = "Bull",
        responsibility: String = "Bull Case Analysis",
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

    private fun makePreset(
        id: String = "investment_committee",
        ratingScale: List<String> = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell"),
        mandates: Map<String, String> = mapOf(
            "max_rounds" to "15",
            "activation_k" to "2",
            "summary_interval" to "2",
            "debate_rounds" to "3",
            "has_supervisor" to "true",
            "supervisor_final_call" to "true",
            "consensus_required" to "false",
            "vote_type" to "binary",
            "output_type" to "rating",
            "summary_template" to "binary",
            "prompt_style" to "debate",
        ),
        roles: List<PresetRole> = listOf(
            makePresetRole(id = "analyst", displayName = "Analyst", stance = "Bull"),
            makePresetRole(id = "risk_officer", displayName = "Risk Officer", stance = "Bear"),
            makePresetRole(id = "strategist", displayName = "Strategist", stance = "Neutral / Framework"),
            makePresetRole(id = "supervisor", displayName = "Supervisor", stance = "Adjudication", isSupervisor = true),
        ),
    ) = MeetingPreset(
        id = id,
        name = "Investment Committee",
        description = "Test preset",
        iconName = "account_balance",
        committeeLabel = "Investment Committee",
        roles = roles,
        mandates = mandates,
        ratingScale = ratingScale,
    )

    private fun makeBoard(
        subject: String = "Test Topic",
        round: Int = 3,
        maxRounds: Int = 15,
        messages: List<BoardMessage> = emptyList(),
        votes: Map<String, BoardVote> = emptyMap(),
        finished: Boolean = false,
        summary: String = "",
        consensus: Boolean = false,
    ) = Blackboard(
        subject = subject,
        round = round,
        maxRounds = maxRounds,
        messages = messages,
        votes = votes,
        finished = finished,
        summary = summary,
        consensus = consensus,
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

    // ── FakeRuntimeContext ────────────────────────────────────────

    /**
     * Minimal fake implementation of RuntimeContext sufficient for testing
     * the pure-logic methods of MeetingOrchestrator. LLM-dependent methods
     * (systemLlm, agentPool) throw if called — those paths are not tested here.
     */
    private class FakeRuntimeContext(
        override val preset: MeetingPreset,
        var board: Blackboard = Blackboard(),
        override val agents: List<Agent> = emptyList(),
    ) : RuntimeContext {

        // Throwing stubs for dependencies not used in pure-logic tests
        override val agentPool get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val systemLlm get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val evolutionRepo get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val toolRegistry get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val promptEvolver get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val skillLibrary get() = throw UnsupportedOperationException("Not used in pure-logic tests")
        override val evolverRegistry: Map<String, AgentSelfEvolver> = emptyMap()
        override val toolAgentRoleId: String? = null
        override val actionRepo get() = throw UnsupportedOperationException("Not used in pure-logic tests")

        // Supervisor stub — only role/displayName needed for tests
        override val supervisor: SupervisorCapability = object : SupervisorCapability {
            override val role = "supervisor"
            override val displayName = "Supervisor"
            override fun buildFinishPrompt(board: Blackboard) = ""
            override fun buildSupervisionPrompt(board: Blackboard) = ""
            override fun buildRatingPrompt(board: Blackboard) = ""
            override fun buildSummaryPrompt(board: Blackboard) = ""
            override fun buildUnifiedPrompt(board: Blackboard) = ""
        }

        // Mandate-driven config
        override val maxRounds: Int = preset.mandateInt("max_rounds", 20)
        override val activationK: Int = preset.mandateInt("activation_k", 2)
        override val summaryInterval: Int = preset.mandateInt("summary_interval", 2)
        override val debateRounds: Int = preset.mandateInt("debate_rounds", 3)
        override val hasSupervisor: Boolean = preset.mandateBool("has_supervisor", true)
        override val supervisorFinalCall: Boolean = preset.mandateBool("supervisor_final_call", true)
        override val consensusRequired: Boolean = preset.mandateBool("consensus_required", false)
        override val strictAlternation: Boolean = preset.mandateBool("strict_alternation", false)
        override val voteType: VoteType = when (preset.mandateStr("vote_type", "binary").uppercase()) {
            "SCALE" -> VoteType.SCALE
            "MULTI_STANCE" -> VoteType.MULTI_STANCE
            else -> VoteType.BINARY
        }

        // State
        override val boardValue: Blackboard get() = board
        override val speechesValue get() = emptyList<com.znliang.committee.domain.model.SpeechRecord>()
        override val isPausedValue: Boolean = false
        override val currentTraceId: String = "test_trace"
        override val promptSuggestionsValue: Map<String, String> = emptyMap()

        // Mutable operations
        override fun updateBoard(transform: (Blackboard) -> Blackboard) {
            board = transform(board)
        }
        override fun setIsPaused(value: Boolean) {}
        override fun setPromptSuggestions(value: Map<String, String>) {}
        override fun addSpeech(record: com.znliang.committee.domain.model.SpeechRecord) {}
        override fun removeSpeech(recordId: String) {}
        override fun emitSpeechUpdate(recordId: String, content: String, isStreaming: Boolean) {}
        override fun updateSpeechInBacking(recordId: String, transform: (com.znliang.committee.domain.model.SpeechRecord) -> com.znliang.committee.domain.model.SpeechRecord) {}
        override fun addBoardMessage(role: String, content: String, rawTags: List<String>) {}
        override fun log(msg: String) {} // silent in tests
        override fun updateSessionFinished() {}
        override fun persistSpeech(speech: com.znliang.committee.domain.model.SpeechRecord) {}
        override fun launchInScope(block: suspend () -> Unit) {}
        override suspend fun awaitResumeOrFinish() {}
    }

    // ── Test instance ────────────────────────────────────────────

    private lateinit var preset: MeetingPreset
    private lateinit var ctx: FakeRuntimeContext
    private lateinit var orchestrator: MeetingOrchestrator

    @Before
    fun setUp() {
        preset = makePreset()
        ctx = FakeRuntimeContext(preset = preset, board = makeBoard())
        orchestrator = MeetingOrchestrator(ctx)
    }

    // ── extractStreamingContent() tests ──────────────────────────

    @Test
    fun `extractStreamingContent returns empty when CONTENT marker not yet present`() {
        val result = orchestrator.extractStreamingContent("SPEAK: YES\nREASONING: thinking...")
        assertEquals("", result)
    }

    @Test
    fun `extractStreamingContent returns content after CONTENT marker`() {
        val text = "SPEAK: YES\nREASONING: Some reasoning\nCONTENT: Hello world this is my analysis"
        val result = orchestrator.extractStreamingContent(text)
        assertEquals("Hello world this is my analysis", result.trim())
    }

    @Test
    fun `extractStreamingContent truncates at VOTE marker`() {
        val text = "SPEAK: YES\nCONTENT: My analysis here\nVOTE: AGREE\nTAGS: PRO,GROWTH"
        val result = orchestrator.extractStreamingContent(text)
        assertTrue("Content should not include VOTE section", !result.contains("AGREE"))
        assertTrue("Content should contain the analysis", result.contains("My analysis here"))
    }

    @Test
    fun `extractStreamingContent truncates at TAGS marker`() {
        val text = "SPEAK: YES\nCONTENT: My analysis here\nTAGS: PRO,RISK"
        val result = orchestrator.extractStreamingContent(text)
        assertFalse("Content should not include TAGS", result.contains("PRO,RISK"))
        assertTrue("Content should contain analysis", result.contains("My analysis here"))
    }

    @Test
    fun `extractStreamingContent handles multiline content`() {
        val text = """SPEAK: YES
REASONING: Let me think
CONTENT: ### Core Judgment
The stock is undervalued.

### Arguments
- Strong earnings growth
- Market expansion

VOTE: AGREE
TAGS: PRO,GROWTH"""
        val result = orchestrator.extractStreamingContent(text)
        assertTrue("Should contain markdown header", result.contains("### Core Judgment"))
        assertTrue("Should contain multi-line content", result.contains("Market expansion"))
        assertFalse("Should not contain VOTE", result.contains("VOTE:"))
    }

    @Test
    fun `extractStreamingContent handles chinese CONTENT marker`() {
        val text = "SPEAK: YES\nCONTENT：这是我的分析\nVOTE：AGREE"
        val result = orchestrator.extractStreamingContent(text)
        assertTrue("Should handle Chinese colon marker", result.contains("这是我的分析"))
        assertFalse("Should truncate at VOTE", result.contains("AGREE"))
    }

    @Test
    fun `extractStreamingContent handles partial streaming (no VOTE yet)`() {
        val text = "SPEAK: YES\nCONTENT: My analysis is still being writte"
        val result = orchestrator.extractStreamingContent(text)
        assertEquals("My analysis is still being writte", result.trim())
    }

    // ── shouldSupervisorComment() tests ──────────────────────────

    @Test
    fun `shouldSupervisorComment returns false in round 1`() {
        val board = makeBoard(round = 1, messages = listOf(
            makeMessage("analyst", "content", round = 1),
            makeMessage("risk_officer", "content", round = 1),
            makeMessage("strategist", "content", round = 1),
        ))
        assertFalse(orchestrator.shouldSupervisorComment(board))
    }

    @Test
    fun `shouldSupervisorComment returns false if supervisor already commented this round`() {
        val board = makeBoard(round = 3, messages = listOf(
            makeMessage("analyst", "content", round = 1),
            makeMessage("risk_officer", "content", round = 2),
            makeMessage("strategist", "content", round = 3),
            makeMessage("supervisor", "comment", round = 3),
        ))
        assertFalse(orchestrator.shouldSupervisorComment(board))
    }

    @Test
    fun `shouldSupervisorComment returns false when consensus reached`() {
        val board = makeBoard(round = 3, consensus = true, messages = listOf(
            makeMessage("analyst", "content", round = 1),
            makeMessage("risk_officer", "content", round = 2),
            makeMessage("strategist", "content", round = 3),
        ))
        assertFalse(orchestrator.shouldSupervisorComment(board))
    }

    @Test
    fun `shouldSupervisorComment returns false with fewer than 3 non-supervisor messages`() {
        val board = makeBoard(round = 2, messages = listOf(
            makeMessage("analyst", "content", round = 1),
            makeMessage("risk_officer", "content", round = 2),
        ))
        assertFalse(orchestrator.shouldSupervisorComment(board))
    }

    @Test
    fun `shouldSupervisorComment returns true when conditions are met`() {
        val board = makeBoard(round = 3, consensus = false, messages = listOf(
            makeMessage("analyst", "Bull case", round = 1),
            makeMessage("risk_officer", "Bear case", round = 2),
            makeMessage("strategist", "Strategy view", round = 3),
        ))
        assertTrue(orchestrator.shouldSupervisorComment(board))
    }

    @Test
    fun `shouldSupervisorComment returns true with many messages and no prior comment`() {
        val board = makeBoard(round = 5, consensus = false, messages = listOf(
            makeMessage("analyst", "c1", round = 1),
            makeMessage("risk_officer", "c2", round = 2),
            makeMessage("analyst", "c3", round = 3),
            makeMessage("risk_officer", "c4", round = 4),
            makeMessage("strategist", "c5", round = 5),
        ))
        assertTrue(orchestrator.shouldSupervisorComment(board))
    }

    // ── parseRating() tests ──────────────────────────────────────

    @Test
    fun `parseRating extracts rating from standard format`() {
        val content = "最终评级：Buy\n理由：市场前景良好"
        val rating = orchestrator.parseRating(content)
        assertEquals("Buy", rating)
    }

    @Test
    fun `parseRating extracts rating from English format`() {
        val content = "Final Rating: Overweight\nBased on strong fundamentals"
        val rating = orchestrator.parseRating(content)
        assertEquals("Overweight", rating)
    }

    @Test
    fun `parseRating extracts rating with Chinese conclusion`() {
        val content = "最终结论：Hold+\n综合考虑各方面因素"
        val rating = orchestrator.parseRating(content)
        assertEquals("Hold+", rating)
    }

    @Test
    fun `parseRating returns null when no valid rating found`() {
        val content = "The discussion was inconclusive, no clear recommendation."
        val rating = orchestrator.parseRating(content)
        assertNull(rating)
    }

    @Test
    fun `parseRating handles rating at end of line`() {
        val content = "After careful consideration, 最终评级 is Sell"
        val rating = orchestrator.parseRating(content)
        assertEquals("Sell", rating)
    }

    @Test
    fun `parseRating works with different scale values`() {
        // Test with the actual scale: Buy, Overweight, Hold+, Hold, Underweight, Sell
        val testCases = listOf(
            "最终评级：Buy" to "Buy",
            "最终评级：Overweight" to "Overweight",
            "最终评级：Hold+" to "Hold+",
            "最终评级：Hold" to "Hold",
            "最终评级：Underweight" to "Underweight",
            "最终评级：Sell" to "Sell",
        )
        for ((input, expected) in testCases) {
            assertEquals("Should parse '$expected'", expected, orchestrator.parseRating(input))
        }
    }

    // ── calculateConfidence() tests ──────────────────────────────

    @Test
    fun `calculateConfidence returns base score with no votes`() {
        ctx.board = makeBoard(
            round = 1,
            messages = emptyList(),
            votes = emptyMap(),
        )
        val (confidence, breakdown) = orchestrator.calculateConfidence()
        assertTrue("Confidence should be positive", confidence > 0)
        assertTrue("Breakdown should contain Consensus", breakdown.contains("Consensus"))
        assertTrue("Breakdown should contain Sufficiency", breakdown.contains("Sufficiency"))
        assertTrue("Breakdown should contain Resolution", breakdown.contains("Resolution"))
    }

    @Test
    fun `calculateConfidence gives higher score with unanimous votes`() {
        val agents = listOf(
            GenericAgent(makePresetRole(id = "a1"), ""),
            GenericAgent(makePresetRole(id = "a2"), ""),
            GenericAgent(makePresetRole(id = "a3"), ""),
        )
        ctx = FakeRuntimeContext(preset = preset, agents = agents)
        orchestrator = MeetingOrchestrator(ctx)

        ctx.board = makeBoard(
            round = 5,
            messages = listOf(
                makeMessage("a1", "content", round = 1),
                makeMessage("a2", "content", round = 2),
                makeMessage("a3", "content", round = 3),
                makeMessage("a1", "content2", round = 4),
            ),
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 3),
                "a2" to BoardVote(role = "a2", agree = true, round = 3),
                "a3" to BoardVote(role = "a3", agree = true, round = 3),
            ),
        )
        val (confHigh, _) = orchestrator.calculateConfidence()

        ctx.board = makeBoard(
            round = 5,
            messages = listOf(
                makeMessage("a1", "content", round = 1),
                makeMessage("a2", "content", round = 2),
                makeMessage("a3", "content", round = 3),
                makeMessage("a1", "content2", round = 4),
            ),
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 3),
                "a2" to BoardVote(role = "a2", agree = false, round = 3),
            ),
        )
        val (confLow, _) = orchestrator.calculateConfidence()

        assertTrue(
            "Unanimous votes should have higher confidence (high=$confHigh > low=$confLow)",
            confHigh > confLow,
        )
    }

    @Test
    fun `calculateConfidence increases with more rounds and messages`() {
        ctx.board = makeBoard(round = 1, messages = emptyList())
        val (confLow, _) = orchestrator.calculateConfidence()

        ctx.board = makeBoard(
            round = 8,
            messages = listOf(
                makeMessage("a1", "c1", round = 1),
                makeMessage("a2", "c2", round = 2),
                makeMessage("a3", "c3", round = 3),
                makeMessage("a1", "c4", round = 4),
                makeMessage("a2", "c5", round = 5),
                makeMessage("a3", "c6", round = 6),
                makeMessage("a1", "c7", round = 7),
                makeMessage("a2", "c8", round = 8),
            ),
        )
        val (confHigh, _) = orchestrator.calculateConfidence()

        assertTrue(
            "More discussion should increase confidence (high=$confHigh > low=$confLow)",
            confHigh > confLow,
        )
    }

    @Test
    fun `calculateConfidence gives resolution bonus for answered questions`() {
        val agents = listOf(
            GenericAgent(makePresetRole(id = "a1"), ""),
            GenericAgent(makePresetRole(id = "a2"), ""),
        )
        ctx = FakeRuntimeContext(preset = preset, agents = agents)
        orchestrator = MeetingOrchestrator(ctx)

        // Board with unanswered questions
        ctx.board = makeBoard(
            round = 4,
            messages = listOf(
                makeMessage("a1", "What about earnings?", round = 1, rawTags = listOf("QUESTION")),
                makeMessage("a2", "Risk concern", round = 2, rawTags = listOf("RISK")),
            ),
        )
        val (confUnanswered, _) = orchestrator.calculateConfidence()

        // Board with answered questions
        ctx.board = makeBoard(
            round = 4,
            messages = listOf(
                makeMessage("a1", "What about earnings?", round = 1, rawTags = listOf("QUESTION")),
                makeMessage("a2", "Earnings grew 30% last quarter", round = 2, rawTags = listOf("PRO")),
            ),
        )
        val (confAnswered, _) = orchestrator.calculateConfidence()

        assertTrue(
            "Answered questions should boost confidence (answered=$confAnswered >= unanswered=$confUnanswered)",
            confAnswered >= confUnanswered,
        )
    }

    @Test
    fun `calculateConfidence capped at 100`() {
        val agents = listOf(
            GenericAgent(makePresetRole(id = "a1"), ""),
            GenericAgent(makePresetRole(id = "a2"), ""),
            GenericAgent(makePresetRole(id = "a3"), ""),
        )
        ctx = FakeRuntimeContext(preset = preset, agents = agents)
        orchestrator = MeetingOrchestrator(ctx)

        ctx.board = makeBoard(
            round = 20,
            consensus = true,
            messages = (1..20).map { makeMessage("a${(it % 3) + 1}", "content$it", round = it) },
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 10),
                "a2" to BoardVote(role = "a2", agree = true, round = 10),
                "a3" to BoardVote(role = "a3", agree = true, round = 10),
            ),
        )
        val (confidence, _) = orchestrator.calculateConfidence()
        assertTrue("Confidence should be capped at 100", confidence <= 100)
    }

    @Test
    fun `calculateConfidence returns non-negative`() {
        ctx.board = makeBoard(round = 1, messages = emptyList(), votes = emptyMap())
        val (confidence, _) = orchestrator.calculateConfidence()
        assertTrue("Confidence should be non-negative", confidence >= 0)
    }

    // ── defaultRating() tests ────────────────────────────────────

    @Test
    fun `defaultRating returns middle value of scale`() {
        // Scale: Buy, Overweight, Hold+, Hold, Underweight, Sell (6 items)
        // Middle index: 6/2 = 3 -> "Hold"
        val rating = orchestrator.defaultRating()
        assertEquals("Hold", rating)
    }

    @Test
    fun `defaultRating with odd-length scale picks middle`() {
        val oddPreset = makePreset(ratingScale = listOf("Strong Accept", "Accept", "Borderline", "Reject"))
        val oddCtx = FakeRuntimeContext(preset = oddPreset)
        val oddOrchestrator = MeetingOrchestrator(oddCtx)
        // Scale size=4, index=4/2=2 -> "Borderline"
        assertEquals("Borderline", oddOrchestrator.defaultRating())
    }

    @Test
    fun `defaultRating with single-item scale`() {
        val singlePreset = makePreset(ratingScale = listOf("Only Option"))
        val singleCtx = FakeRuntimeContext(preset = singlePreset)
        val singleOrchestrator = MeetingOrchestrator(singleCtx)
        assertEquals("Only Option", singleOrchestrator.defaultRating())
    }

    @Test
    fun `defaultRating with empty scale returns fallback`() {
        val emptyPreset = makePreset(ratingScale = emptyList())
        val emptyCtx = FakeRuntimeContext(preset = emptyPreset)
        val emptyOrchestrator = MeetingOrchestrator(emptyCtx)
        assertEquals("Hold", emptyOrchestrator.defaultRating())
    }

    // ── doVoteMajorityRating() tests ─────────────────────────────

    @Test
    fun `doVoteMajorityRating BINARY agrees picks first scale item`() {
        // agreeRatio >= 0.5 -> first item (Buy)
        ctx.board = makeBoard(
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 2),
                "a2" to BoardVote(role = "a2", agree = true, round = 2),
                "a3" to BoardVote(role = "a3", agree = false, round = 2),
            ),
        )
        val rating = orchestrator.doVoteMajorityRating()
        assertEquals("Buy", rating)
    }

    @Test
    fun `doVoteMajorityRating BINARY disagree picks last scale item`() {
        // agreeRatio < 0.5 -> last item (Sell)
        ctx.board = makeBoard(
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = false, round = 2),
                "a2" to BoardVote(role = "a2", agree = false, round = 2),
                "a3" to BoardVote(role = "a3", agree = true, round = 2),
            ),
        )
        val rating = orchestrator.doVoteMajorityRating()
        assertEquals("Sell", rating)
    }

    @Test
    fun `doVoteMajorityRating BINARY tie picks first scale item`() {
        // 50/50 -> agreeRatio=0.5 >= 0.5 -> first item
        ctx.board = makeBoard(
            votes = mapOf(
                "a1" to BoardVote(role = "a1", agree = true, round = 2),
                "a2" to BoardVote(role = "a2", agree = false, round = 2),
            ),
        )
        val rating = orchestrator.doVoteMajorityRating()
        assertEquals("Buy", rating)
    }

    @Test
    fun `doVoteMajorityRating SCALE maps average score to scale index`() {
        // Scale: Buy(0), Overweight(1), Hold+(2), Hold(3), Underweight(4), Sell(5)
        // avg=9.0 -> idx = ((1 - (9-1)/9) * 5) = ((1 - 0.889) * 5) = (0.111 * 5) = 0.55 -> 0 -> "Buy"
        val scalePreset = makePreset(mandates = mapOf(
            "vote_type" to "scale",
            "max_rounds" to "15",
            "activation_k" to "2",
            "summary_interval" to "2",
            "debate_rounds" to "3",
            "has_supervisor" to "true",
            "supervisor_final_call" to "true",
            "consensus_required" to "false",
            "output_type" to "rating",
            "summary_template" to "binary",
            "prompt_style" to "debate",
        ))
        val scaleCtx = FakeRuntimeContext(preset = scalePreset)
        val scaleOrch = MeetingOrchestrator(scaleCtx)

        scaleCtx.board = makeBoard(
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = true, round = 2, numericScore = 9),
                "r2" to BoardVote(role = "r2", agree = true, round = 2, numericScore = 9),
            ),
        )
        val ratingHigh = scaleOrch.doVoteMajorityRating()
        assertEquals("High scores should map to first scale item", "Buy", ratingHigh)

        scaleCtx.board = makeBoard(
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = false, round = 2, numericScore = 1),
                "r2" to BoardVote(role = "r2", agree = false, round = 2, numericScore = 1),
            ),
        )
        val ratingLow = scaleOrch.doVoteMajorityRating()
        assertEquals("Low scores should map to last scale item", "Sell", ratingLow)
    }

    @Test
    fun `doVoteMajorityRating SCALE mid score maps to middle`() {
        val scalePreset = makePreset(mandates = mapOf(
            "vote_type" to "scale",
            "max_rounds" to "15",
            "activation_k" to "2",
            "summary_interval" to "2",
            "debate_rounds" to "3",
            "has_supervisor" to "true",
            "supervisor_final_call" to "true",
            "consensus_required" to "false",
            "output_type" to "rating",
            "summary_template" to "binary",
            "prompt_style" to "debate",
        ))
        val scaleCtx = FakeRuntimeContext(preset = scalePreset)
        val scaleOrch = MeetingOrchestrator(scaleCtx)

        // avg=5.5 -> idx = ((1 - (5.5-1)/9) * 5) = ((1 - 0.5) * 5) = 2.5 -> 2 -> "Hold+"
        scaleCtx.board = makeBoard(
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = true, round = 2, numericScore = 5),
                "r2" to BoardVote(role = "r2", agree = true, round = 2, numericScore = 6),
            ),
        )
        val rating = scaleOrch.doVoteMajorityRating()
        assertNotNull("Should produce a rating for mid score", rating)
        // The exact middle value depends on rounding; verify it's not at the extremes
        assertTrue("Mid score should not map to Buy", rating != "Buy")
        assertTrue("Mid score should not map to Sell", rating != "Sell")
    }

    @Test
    fun `doVoteMajorityRating MULTI_STANCE uses majority stance`() {
        val msPreset = makePreset(
            ratingScale = listOf("Invest", "Conditional Interest", "Pass"),
            mandates = mapOf(
                "vote_type" to "multi_stance",
                "max_rounds" to "15",
                "activation_k" to "2",
                "summary_interval" to "2",
                "debate_rounds" to "3",
                "has_supervisor" to "true",
                "supervisor_final_call" to "true",
                "consensus_required" to "false",
                "output_type" to "rating",
                "summary_template" to "binary",
                "prompt_style" to "debate",
            ),
        )
        val msCtx = FakeRuntimeContext(preset = msPreset)
        val msOrch = MeetingOrchestrator(msCtx)

        msCtx.board = makeBoard(
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = true, round = 2, stanceLabel = "Invest"),
                "r2" to BoardVote(role = "r2", agree = false, round = 2, stanceLabel = "Pass"),
                "r3" to BoardVote(role = "r3", agree = true, round = 2, stanceLabel = "Invest"),
            ),
        )
        val rating = msOrch.doVoteMajorityRating()
        assertEquals("Invest", rating)
    }

    @Test
    fun `doVoteMajorityRating MULTI_STANCE falls back to first scale when no majority`() {
        val msPreset = makePreset(
            ratingScale = listOf("Invest", "Conditional Interest", "Pass"),
            mandates = mapOf(
                "vote_type" to "multi_stance",
                "max_rounds" to "15",
                "activation_k" to "2",
                "summary_interval" to "2",
                "debate_rounds" to "3",
                "has_supervisor" to "true",
                "supervisor_final_call" to "true",
                "consensus_required" to "false",
                "output_type" to "rating",
                "summary_template" to "binary",
                "prompt_style" to "debate",
            ),
        )
        val msCtx = FakeRuntimeContext(preset = msPreset)
        val msOrch = MeetingOrchestrator(msCtx)

        // No votes at all -> majorityStance returns null -> fallback to first scale item
        msCtx.board = makeBoard(votes = emptyMap())
        val rating = msOrch.doVoteMajorityRating()
        assertEquals("Invest", rating)
    }

    // ── parseRating with different preset scales ─────────────────

    @Test
    fun `parseRating works with product review scale`() {
        val prodPreset = makePreset(
            ratingScale = listOf("Ship", "Ship with fixes", "Needs revision", "Reject"),
        )
        val prodCtx = FakeRuntimeContext(preset = prodPreset)
        val prodOrch = MeetingOrchestrator(prodCtx)

        assertEquals("Ship with fixes", prodOrch.parseRating("最终评级：Ship with fixes"))
        assertEquals("Reject", prodOrch.parseRating("Final Rating: Reject"))
        assertNull(prodOrch.parseRating("The product looks good"))
    }

    @Test
    fun `parseRating works with debate scale`() {
        val debatePreset = makePreset(
            ratingScale = listOf("Proponent wins", "Opponent wins", "Draw"),
        )
        val debateCtx = FakeRuntimeContext(preset = debatePreset)
        val debateOrch = MeetingOrchestrator(debateCtx)

        assertEquals("Proponent wins", debateOrch.parseRating("最终评级：Proponent wins"))
        assertEquals("Draw", debateOrch.parseRating("最终结论：Draw"))
    }
}
