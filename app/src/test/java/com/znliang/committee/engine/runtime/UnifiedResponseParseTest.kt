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
        assertNull(resp.voteBull) // 空 VOTE: 不应产生投票
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

    // ── 兜底逻辑测试 ──────────────────────────────────────────

    @Test
    fun `parse unstructured text as content fallback`() {
        // LLM 完全没遵循格式，返回自然语言
        val raw = "I believe this stock is a strong buy based on the Q3 earnings report. The revenue growth of 25% YoY is impressive."

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.wantsToSpeak) // 有内容就视为想发言
        assertTrue(resp.content.contains("strong buy"))
        assertNull(resp.voteBull) // 无法从非结构化文本中提取投票
    }

    @Test
    fun `parse Chinese colon variants`() {
        val raw = """
            SPEAK：YES
            CONTENT：这是中文冒号测试。
            VOTE：AGREE
            TAGS：PRO，GROWTH
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.wantsToSpeak)
        assertTrue(resp.content.contains("中文冒号"))
        assertTrue(resp.voteBull!!)
    }

    @Test
    fun `parse Chinese vote keywords`() {
        val raw = """
            SPEAK: YES
            CONTENT: 我认为估值合理。
            VOTE: 同意
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.voteBull!!)
    }

    @Test
    fun `parse SPEAK absent defaults to true when content present`() {
        // 没有 SPEAK 行，但有 CONTENT
        val raw = """
            CONTENT: Analysis shows positive momentum.
            VOTE: AGREE
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true)
        assertTrue(resp.wantsToSpeak) // 有内容默认发言
        assertTrue(resp.content.contains("positive momentum"))
    }

    @Test
    fun `parse SCALE vote with Chinese 分`() {
        val raw = """
            SPEAK: YES
            CONTENT: Evaluation complete.
            VOTE: 8分
        """.trimIndent()

        val resp = UnifiedResponse.parse(raw, canVote = true, voteType = VoteType.SCALE)
        assertEquals(8, resp.numericScore)
        assertTrue(resp.voteBull!!)
    }
}
