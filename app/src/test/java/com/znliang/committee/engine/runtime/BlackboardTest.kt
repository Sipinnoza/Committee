package com.znliang.committee.engine.runtime

import org.junit.Assert.*
import org.junit.Test

class BlackboardTest {

    @Test
    fun `agreeRatio returns 0 when no votes`() {
        val board = Blackboard()
        assertEquals(0f, board.agreeRatio(), 0.001f)
    }

    @Test
    fun `agreeRatio returns correct ratio`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
                "b" to BoardVote(role = "b", agree = true, round = 1),
                "c" to BoardVote(role = "c", agree = false, round = 1),
            )
        )
        assertEquals(2f / 3f, board.agreeRatio(), 0.001f)
    }

    @Test
    fun `disagreeRatio is complement of agreeRatio`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
                "b" to BoardVote(role = "b", agree = false, round = 1),
            )
        )
        assertEquals(1f - board.agreeRatio(), board.disagreeRatio(), 0.001f)
    }

    @Test
    fun `hasConsensus BINARY requires 3 votes and 70 percent`() {
        // 2 votes — not enough
        val board2 = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
                "b" to BoardVote(role = "b", agree = true, round = 1),
            )
        )
        assertFalse(board2.hasConsensus(VoteType.BINARY))

        // 3 votes, 100% agree — consensus
        val board3 = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
                "b" to BoardVote(role = "b", agree = true, round = 1),
                "c" to BoardVote(role = "c", agree = true, round = 1),
            )
        )
        assertTrue(board3.hasConsensus(VoteType.BINARY))

        // 3 votes, 33% agree — no consensus
        val board3split = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
                "b" to BoardVote(role = "b", agree = false, round = 1),
                "c" to BoardVote(role = "c", agree = false, round = 1),
            )
        )
        // disagreeRatio = 66%, not > 70%
        assertFalse(board3split.hasConsensus(VoteType.BINARY))
    }

    @Test
    fun `hasConsensus SCALE checks score spread`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1, numericScore = 7),
                "b" to BoardVote(role = "b", agree = true, round = 1, numericScore = 8),
            )
        )
        assertTrue(board.hasConsensus(VoteType.SCALE)) // spread = 1 <= 2

        val boardWide = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1, numericScore = 3),
                "b" to BoardVote(role = "b", agree = true, round = 1, numericScore = 8),
            )
        )
        assertFalse(boardWide.hasConsensus(VoteType.SCALE)) // spread = 5 > 2
    }

    @Test
    fun `averageScore computes correctly`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1, numericScore = 6),
                "b" to BoardVote(role = "b", agree = true, round = 1, numericScore = 8),
            )
        )
        assertEquals(7f, board.averageScore(), 0.001f)
    }

    @Test
    fun `majorityStance returns most common stance`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1, stanceLabel = "Approve"),
                "b" to BoardVote(role = "b", agree = true, round = 1, stanceLabel = "Approve"),
                "c" to BoardVote(role = "c", agree = false, round = 1, stanceLabel = "Reject"),
            )
        )
        assertEquals("Approve", board.majorityStance())
    }

    @Test
    fun `inferPhase returns IDLE for empty board`() {
        assertEquals(BoardPhase.IDLE, Blackboard().inferPhase())
    }

    @Test
    fun `inferPhase returns DEBATE after round 1`() {
        val board = Blackboard(
            round = 2,
            messages = listOf(
                BoardMessage(role = "a", content = "test", round = 1),
                BoardMessage(role = "b", content = "test", round = 1),
                BoardMessage(role = "c", content = "test", round = 1),
                BoardMessage(role = "d", content = "test", round = 2),
            )
        )
        assertEquals(BoardPhase.DEBATE, board.inferPhase())
    }

    @Test
    fun `inferPhase returns VOTE when enough votes`() {
        val board = Blackboard(
            round = 3,
            messages = listOf(BoardMessage(role = "a", content = "test", round = 1)),
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 2),
                "b" to BoardVote(role = "b", agree = false, round = 2),
            )
        )
        assertEquals(BoardPhase.VOTE, board.inferPhase())
    }

    @Test
    fun `inferPhase returns RATING when finalRating set`() {
        val board = Blackboard(finalRating = "Buy")
        assertEquals(BoardPhase.RATING, board.inferPhase())
    }

    @Test
    fun `inferPhase returns initialPhase when no messages`() {
        val board = Blackboard(initialPhase = BoardPhase.ANALYSIS)
        assertEquals(BoardPhase.ANALYSIS, board.inferPhase())
    }

    @Test
    fun `messagesByTags filters correctly`() {
        val board = Blackboard(
            messages = listOf(
                BoardMessage(role = "a", content = "bullish", round = 1, rawTags = listOf("PRO")),
                BoardMessage(role = "b", content = "bearish", round = 1, rawTags = listOf("CON")),
                BoardMessage(role = "c", content = "risk analysis", round = 1, rawTags = listOf("RISK")),
            )
        )
        val proMsgs = board.messagesByTags(MsgTag.PRO)
        assertEquals(1, proMsgs.size)
        assertEquals("a", proMsgs[0].role)
    }

    @Test
    fun `votes map deduplicates by role`() {
        val board = Blackboard(
            votes = mapOf(
                "a" to BoardVote(role = "a", agree = true, round = 1),
            )
        )
        val updated = board.copy(
            votes = board.votes + ("a" to BoardVote(role = "a", agree = false, round = 2))
        )
        assertEquals(1, updated.votes.size)
        assertFalse(updated.votes["a"]!!.agree) // Latest vote wins
    }
}
