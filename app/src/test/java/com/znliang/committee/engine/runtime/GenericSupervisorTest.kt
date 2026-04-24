package com.znliang.committee.engine.runtime

import com.znliang.committee.domain.model.MeetingPreset
import com.znliang.committee.domain.model.PresetRole
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GenericSupervisor — verifies prompt generation for
 * finish, summary, rating, and supervision phases, plus eligibility.
 */
class GenericSupervisorTest {

    // ── Test fixtures ────────────────────────────────────────────

    private fun makePresetRole(
        id: String = "supervisor",
        displayName: String = "Supervisor",
        stance: String = "Adjudication",
        responsibility: String = "Arbitration + Minutes + Execution Discipline Tracking",
        isSupervisor: Boolean = true,
    ) = PresetRole(
        id = id,
        displayName = displayName,
        stance = stance,
        responsibility = responsibility,
        systemPromptKey = "role_$id",
        colorHex = "#607D8B",
        isSupervisor = isSupervisor,
    )

    private fun makePreset(
        id: String = "investment_committee",
        committeeLabel: String = "Investment Committee",
        ratingScale: List<String> = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell"),
        mandates: Map<String, String> = mapOf(
            "output_type" to "rating",
            "summary_template" to "binary",
            "vote_type" to "binary",
        ),
        roles: List<PresetRole> = listOf(
            makePresetRole(id = "analyst", displayName = "Analyst", stance = "Bull", isSupervisor = false),
            makePresetRole(id = "risk_officer", displayName = "Risk Officer", stance = "Bear", isSupervisor = false),
            makePresetRole(),
        ),
    ) = MeetingPreset(
        id = id,
        name = "Investment Committee",
        description = "Test preset",
        iconName = "account_balance",
        committeeLabel = committeeLabel,
        roles = roles,
        mandates = mandates,
        ratingScale = ratingScale,
    )

    private fun makeSupervisor(
        presetRole: PresetRole = makePresetRole(),
        ratingScale: List<String> = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell"),
        committeeLabel: String = "Investment Committee",
        preset: MeetingPreset? = makePreset(),
    ) = GenericSupervisor(
        presetRole = presetRole,
        ratingScale = ratingScale,
        committeeLabel = committeeLabel,
        preset = preset,
    )

    private fun makeBoard(
        subject: String = "NVDA Stock Analysis",
        round: Int = 3,
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
    fun `eligible returns true when board is not finished`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(finished = false)
        assertTrue(supervisor.eligible(board))
    }

    @Test
    fun `eligible returns false when board is finished`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(finished = true)
        assertFalse(supervisor.eligible(board))
    }

    @Test
    fun `eligible returns true regardless of messages or rounds`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            round = 10,
            messages = listOf(
                makeMessage("supervisor", "Comment 1", round = 9),
                makeMessage("supervisor", "Comment 2", round = 10),
            ),
        )
        assertTrue("Supervisor eligibility only depends on finished flag", supervisor.eligible(board))
    }

    // ── canVote tests ────────────────────────────────────────────

    @Test
    fun `canVote is always false for supervisor`() {
        val supervisor = makeSupervisor()
        assertFalse("Supervisor should not vote", supervisor.canVote)
    }

    // ── buildUnifiedPrompt tests ─────────────────────────────────

    @Test
    fun `buildUnifiedPrompt returns empty string`() {
        val supervisor = makeSupervisor()
        val board = makeBoard()
        assertEquals("Supervisor buildUnifiedPrompt should be empty", "", supervisor.buildUnifiedPrompt(board))
    }

    // ── buildFinishPrompt() tests ────────────────────────────────

    @Test
    fun `buildFinishPrompt contains subject and round info`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            subject = "AAPL Earnings Review",
            round = 5,
            messages = listOf(
                makeMessage("analyst", "Bull case here", round = 1),
                makeMessage("risk_officer", "Bear case here", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Finish prompt should contain subject", prompt.contains("AAPL Earnings Review"))
        assertTrue("Finish prompt should mention round", prompt.contains("5"))
    }

    @Test
    fun `buildFinishPrompt counts debate messages excluding supervisor`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "Bull analysis", round = 1),
                makeMessage("risk_officer", "Bear case", round = 2),
                makeMessage("supervisor", "Comment", round = 2),
                makeMessage("analyst", "Rebuttal", round = 3),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        // debateCount = messages where role != supervisor's role
        // Since supervisor.role = "supervisor", only non-supervisor messages count
        assertTrue("Finish prompt should count 3 non-supervisor messages", prompt.contains("3"))
    }

    @Test
    fun `buildFinishPrompt includes vote summary for binary votes`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            votes = mapOf(
                "analyst" to BoardVote(role = "analyst", agree = true, round = 2),
                "risk_officer" to BoardVote(role = "risk_officer", agree = false, round = 2),
            ),
            messages = listOf(
                makeMessage("analyst", "Bull", round = 1),
                makeMessage("risk_officer", "Bear", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Should show agree count", prompt.contains("1") && prompt.contains("赞成"))
        assertTrue("Should show disagree count", prompt.contains("1") && prompt.contains("反对"))
    }

    @Test
    fun `buildFinishPrompt includes participant statistics`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "First speech", round = 1),
                makeMessage("analyst", "Second speech", round = 2),
                makeMessage("risk_officer", "Risk view", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Should list participant stats", prompt.contains("analyst"))
        assertTrue("Should mention participant count", prompt.contains("2人"))
    }

    @Test
    fun `buildFinishPrompt contains YES or NO instruction`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Should end with YES/NO instruction", prompt.contains("YES") && prompt.contains("NO"))
    }

    @Test
    fun `buildFinishPrompt uses binary criterion for binary template`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "summary_template" to "binary",
            "vote_type" to "binary",
            "output_type" to "rating",
        )))
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "content", round = 1),
                makeMessage("risk_officer", "content", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Binary template should mention opposing sides",
            prompt.contains("正反双方"))
    }

    @Test
    fun `buildFinishPrompt uses multi_dimension criterion for that template`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "summary_template" to "multi_dimension",
            "vote_type" to "binary",
            "output_type" to "rating",
        )))
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "content", round = 1),
                makeMessage("risk_officer", "content", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Multi-dimension template should mention dimensions",
            prompt.contains("各维度"))
    }

    @Test
    fun `buildFinishPrompt uses convergent criterion for that template`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "summary_template" to "convergent",
            "vote_type" to "binary",
            "output_type" to "rating",
        )))
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "content", round = 1),
                makeMessage("risk_officer", "content", round = 2),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Convergent template should mention convergence",
            prompt.contains("收敛"))
    }

    @Test
    fun `buildFinishPrompt uses summary when available`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            summary = "The bulls argue strong earnings while bears warn about valuation",
            messages = listOf(
                makeMessage("analyst", "content", round = 1),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Should include existing summary",
            prompt.contains("摘要") && prompt.contains("bulls argue"))
    }

    // ── buildSummaryPrompt() tests ───────────────────────────────

    @Test
    fun `buildSummaryPrompt contains all messages`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            subject = "TSLA Review",
            round = 4,
            messages = listOf(
                makeMessage("analyst", "Strong revenue growth potential", round = 1),
                makeMessage("risk_officer", "Valuation is stretched", round = 2),
                makeMessage("analyst", "Market share expanding", round = 3),
            ),
        )
        val prompt = supervisor.buildSummaryPrompt(board)
        assertTrue("Should contain subject", prompt.contains("TSLA Review"))
        assertTrue("Should contain analyst messages", prompt.contains("Strong revenue"))
        assertTrue("Should contain risk_officer messages", prompt.contains("Valuation is stretched"))
    }

    @Test
    fun `buildSummaryPrompt binary template has pro and con sections`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "summary_template" to "binary",
            "vote_type" to "binary",
            "output_type" to "rating",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSummaryPrompt(board)
        assertTrue("Binary summary should have pro section", prompt.contains("正方观点"))
        assertTrue("Binary summary should have con section", prompt.contains("反方观点"))
    }

    @Test
    fun `buildSummaryPrompt multi_dimension template has dimension sections`() {
        val preset = makePreset(
            mandates = mapOf(
                "summary_template" to "multi_dimension",
                "vote_type" to "binary",
                "output_type" to "rating",
            ),
        )
        val supervisor = makeSupervisor(preset = preset)
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSummaryPrompt(board)
        // multi_dimension lists each non-supervisor role's dimension
        assertTrue("Should contain dimension prompt for each role",
            prompt.contains("维度"))
    }

    @Test
    fun `buildSummaryPrompt convergent template has consensus section`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "summary_template" to "convergent",
            "vote_type" to "binary",
            "output_type" to "rating",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSummaryPrompt(board)
        assertTrue("Convergent should have consensus section", prompt.contains("已达成共识"))
        assertTrue("Convergent should have divergence section", prompt.contains("仍有分歧"))
        assertTrue("Convergent should have next steps", prompt.contains("建议下一步"))
    }

    @Test
    fun `buildSummaryPrompt includes existing summary when present`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            summary = "Previous summary of the debate",
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSummaryPrompt(board)
        assertTrue("Should reference existing summary",
            prompt.contains("前次摘要") && prompt.contains("Previous summary"))
    }

    // ── buildRatingPrompt() tests ────────────────────────────────

    @Test
    fun `buildRatingPrompt contains rating scale`() {
        val scale = listOf("Buy", "Overweight", "Hold+", "Hold", "Underweight", "Sell")
        val supervisor = makeSupervisor(ratingScale = scale)
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "Strong buy signal", round = 1),
                makeMessage("risk_officer", "Too expensive", round = 2),
            ),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Rating prompt should contain the full scale",
            prompt.contains("Buy/Overweight/Hold+/Hold/Underweight/Sell"))
    }

    @Test
    fun `buildRatingPrompt includes message history`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "Revenue growth of 30%", round = 1),
                makeMessage("risk_officer", "PE ratio is 45x", round = 2),
            ),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Should contain message content",
            prompt.contains("Revenue growth") && prompt.contains("PE ratio"))
    }

    @Test
    fun `buildRatingPrompt includes vote details`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
            votes = mapOf(
                "analyst" to BoardVote(role = "analyst", agree = true, round = 1),
                "risk_officer" to BoardVote(role = "risk_officer", agree = false, round = 2),
            ),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Rating prompt should include vote information",
            prompt.contains("投票") || prompt.contains("analyst"))
    }

    @Test
    fun `buildRatingPrompt uses rating output type format`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "output_type" to "rating",
            "summary_template" to "binary",
            "vote_type" to "binary",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Rating output type should mention final rating",
            prompt.contains("最终评定"))
    }

    @Test
    fun `buildRatingPrompt uses decision output type format`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "output_type" to "decision",
            "summary_template" to "binary",
            "vote_type" to "binary",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Decision output type should mention final decision",
            prompt.contains("最终决定"))
        assertTrue("Decision output type should mention action items",
            prompt.contains("行动项"))
    }

    @Test
    fun `buildRatingPrompt uses score output type format`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "output_type" to "score",
            "summary_template" to "binary",
            "vote_type" to "binary",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Score output type should mention scoring",
            prompt.contains("综合评分"))
        assertTrue("Score output type should mention dimensions",
            prompt.contains("各维度评分"))
    }

    @Test
    fun `buildRatingPrompt uses open output type format`() {
        val supervisor = makeSupervisor(preset = makePreset(mandates = mapOf(
            "output_type" to "open",
            "summary_template" to "binary",
            "vote_type" to "binary",
        )))
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Open output type should be free-form",
            prompt.contains("自由形式"))
    }

    @Test
    fun `buildRatingPrompt uses summary when available for history`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            summary = "The bull case focuses on earnings growth",
            messages = listOf(
                makeMessage("analyst", "content1", round = 1),
                makeMessage("risk_officer", "content2", round = 2),
            ),
        )
        val prompt = supervisor.buildRatingPrompt(board)
        assertTrue("Should include summary in history section",
            prompt.contains("讨论摘要") && prompt.contains("bull case focuses"))
    }

    // ── buildSupervisionPrompt() tests ───────────────────────────

    @Test
    fun `buildSupervisionPrompt contains subject and round`() {
        val supervisor = makeSupervisor(committeeLabel = "Investment Committee")
        val board = makeBoard(
            subject = "MSFT Cloud Strategy",
            round = 4,
            maxRounds = 15,
        )
        val prompt = supervisor.buildSupervisionPrompt(board)
        assertTrue("Should contain committee label", prompt.contains("Investment Committee"))
        assertTrue("Should contain subject", prompt.contains("MSFT Cloud Strategy"))
        assertTrue("Should contain round info", prompt.contains("4/15"))
    }

    @Test
    fun `buildSupervisionPrompt includes recent messages`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            messages = listOf(
                makeMessage("analyst", "Strong buy signal due to AI growth", round = 3),
                makeMessage("risk_officer", "Regulatory risks are increasing", round = 3),
            ),
        )
        val prompt = supervisor.buildSupervisionPrompt(board)
        assertTrue("Should contain recent message content",
            prompt.contains("Strong buy signal") || prompt.contains("AI growth"))
        assertTrue("Should contain risk officer content",
            prompt.contains("Regulatory risks"))
    }

    @Test
    fun `buildSupervisionPrompt includes vote summary when votes exist`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            votes = mapOf(
                "analyst" to BoardVote(role = "analyst", agree = true, round = 2),
            ),
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSupervisionPrompt(board)
        assertTrue("Should include vote info when votes present",
            prompt.contains("赞成") || prompt.contains("投票"))
    }

    @Test
    fun `buildSupervisionPrompt excludes vote summary when no votes`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            votes = emptyMap(),
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSupervisionPrompt(board)
        // When no votes, the voteSummary should not be appended
        assertFalse("Should not mention vote counts when empty",
            prompt.contains("赞成") && prompt.contains("反对"))
    }

    @Test
    fun `buildSupervisionPrompt includes summary when available`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(
            summary = "Discussion is trending bullish with risk concerns",
            messages = listOf(makeMessage("analyst", "content", round = 1)),
        )
        val prompt = supervisor.buildSupervisionPrompt(board)
        assertTrue("Should include discussion summary",
            prompt.contains("讨论摘要") && prompt.contains("trending bullish"))
    }

    @Test
    fun `buildSupervisionPrompt asks for brief comment within limit`() {
        val supervisor = makeSupervisor()
        val board = makeBoard(messages = listOf(makeMessage("analyst", "content", round = 1)))
        val prompt = supervisor.buildSupervisionPrompt(board)
        assertTrue("Should request brief response", prompt.contains("100字以内"))
    }

    // ── Vote summary format tests ────────────────────────────────

    @Test
    fun `buildFinishPrompt shows scale vote summary`() {
        val preset = makePreset(mandates = mapOf(
            "vote_type" to "scale",
            "summary_template" to "binary",
            "output_type" to "rating",
        ))
        val supervisor = makeSupervisor(preset = preset)
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = true, round = 1, numericScore = 7),
                "r2" to BoardVote(role = "r2", agree = true, round = 1, numericScore = 8),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Scale votes should show average", prompt.contains("评分") || prompt.contains("平均"))
    }

    @Test
    fun `buildFinishPrompt shows multi_stance vote summary`() {
        val preset = makePreset(mandates = mapOf(
            "vote_type" to "multi_stance",
            "summary_template" to "binary",
            "output_type" to "rating",
        ))
        val supervisor = makeSupervisor(preset = preset)
        val board = makeBoard(
            messages = listOf(makeMessage("analyst", "content", round = 1)),
            votes = mapOf(
                "r1" to BoardVote(role = "r1", agree = true, round = 1, stanceLabel = "Invest"),
                "r2" to BoardVote(role = "r2", agree = false, round = 1, stanceLabel = "Pass"),
            ),
        )
        val prompt = supervisor.buildFinishPrompt(board)
        assertTrue("Multi-stance votes should show stance labels",
            prompt.contains("Invest") || prompt.contains("Pass"))
    }
}
