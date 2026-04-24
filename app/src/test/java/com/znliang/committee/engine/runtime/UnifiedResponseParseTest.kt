package com.znliang.committee.engine.runtime

import org.junit.Assert.*
import org.junit.Test

class UnifiedResponseParseTest {

    @Test
    fun `parse basic SPEAK-YES with content and vote`() {
        val raw = """
            SPEAK: YES
            REASONING: I think the stock is undervalued
            CONTENT: Based on my analysis, the PE ratio is low.
            VOTE: BULL
            TAGS: VALUATION, PRO
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.wantsToSpeak)
        assertEquals("Based on my analysis, the PE ratio is low.", resp.content)
        assertTrue(resp.voteBull!!)
        assertEquals(listOf("VALUATION", "PRO"), resp.rawTags)
        assertEquals("I think the stock is undervalued", resp.reasoning)
    }

    @Test
    fun `parse SPEAK-NO yields no content`() {
        val raw = """
            SPEAK: NO
            REASONING: Nothing new to add
            CONTENT:
            VOTE:
            TAGS:
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertFalse(resp.wantsToSpeak)
        assertEquals("", resp.content)
        assertFalse(resp.voteBull!!)
    }

    @Test
    fun `parse multiline content preserves all lines`() {
        val raw = """
            SPEAK: YES
            CONTENT: Line one.
            Line two.
            Line three.
            VOTE: BEAR
            TAGS: CON
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.content.contains("Line one."))
        assertTrue(resp.content.contains("Line two."))
        assertTrue(resp.content.contains("Line three."))
        assertFalse(resp.voteBull!!)
    }

    @Test
    fun `parse SCALE vote type extracts numeric score`() {
        val raw = """
            SPEAK: YES
            CONTENT: Score-based evaluation.
            VOTE: 7/10
            TAGS: GENERAL
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true, voteType = VoteType.SCALE)
        assertEquals(7, resp.numericScore)
        assertTrue(resp.voteBull!!) // 7 >= 6
    }

    @Test
    fun `parse MULTI_STANCE vote type extracts stance label`() {
        val raw = """
            SPEAK: YES
            CONTENT: I believe the proposal should be approved.
            VOTE: Agree with conditions
            TAGS: PRO
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true, voteType = VoteType.MULTI_STANCE)
        assertEquals("Agree with conditions", resp.stanceLabel)
        assertTrue(resp.voteBull!!) // contains "AGREE"
    }

    @Test
    fun `parse without explicit tags infers from content`() {
        val raw = """
            SPEAK: YES
            CONTENT: 当前PE估值偏低，存在看多机会。
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = false)
        assertTrue(resp.normalizedTags.contains(MsgTag.VALUATION) || resp.normalizedTags.contains(MsgTag.PRO))
    }

    @Test
    fun `parse canVote false ignores vote section`() {
        val raw = """
            SPEAK: YES
            CONTENT: Analysis complete.
            VOTE: BULL
            TAGS: GENERAL
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = false)
        assertNull(resp.voteBull)
    }

    @Test
    fun `parse case insensitive keywords`() {
        val raw = """
            speak: yes
            content: Something here
            vote: bear
            tags: risk
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.wantsToSpeak)
        assertFalse(resp.voteBull!!)
    }
}
