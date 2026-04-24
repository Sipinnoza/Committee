package com.znliang.committee.engine.sse

import android.util.Log
import com.google.gson.Gson
import com.znliang.committee.engine.StreamResult
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class SseParserTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── OpenAI SSE 解析 ──────────────────────────────────────────

    @Test
    fun `parseOpenAiSse extracts tokens from data lines`() {
        val sseData = """
            data: {"choices":[{"delta":{"content":"Hello"}}]}
            data: {"choices":[{"delta":{"content":" world"}}]}
            data: [DONE]
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)

        assertEquals(2, tokenCount)
        val t1 = channel.tryReceive().getOrNull() as StreamResult.Token
        assertEquals("Hello", t1.text)
        val t2 = channel.tryReceive().getOrNull() as StreamResult.Token
        assertEquals(" world", t2.text)
    }

    @Test
    fun `parseOpenAiSse ignores non-data lines`() {
        val sseData = """
            event: message
            id: 1
            data: {"choices":[{"delta":{"content":"ok"}}]}
            : comment
            data: [DONE]
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)

        assertEquals(1, tokenCount)
    }

    @Test
    fun `parseOpenAiSse handles empty content gracefully`() {
        val sseData = """
            data: {"choices":[{"delta":{"content":""}}]}
            data: {"choices":[{"delta":{"role":"assistant"}}]}
            data: {"choices":[{"delta":{"content":"text"}}]}
            data: [DONE]
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)

        assertEquals(1, tokenCount) // Only "text" is non-empty
    }

    @Test
    fun `parseOpenAiSse handles malformed JSON gracefully`() {
        val sseData = """
            data: {invalid json}
            data: {"choices":[{"delta":{"content":"ok"}}]}
            data: [DONE]
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)

        assertEquals(1, tokenCount) // Only valid line processed
    }

    // ── Anthropic SSE 解析 ──────────────────────────────────────

    @Test
    fun `parseAnthropicSse extracts text_delta content`() {
        val sseData = """
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":" world"}}
            data: {"type":"message_stop"}
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseAnthropicSse(reader, gson, channel)

        assertEquals(2, tokenCount)
        val t1 = channel.tryReceive().getOrNull() as StreamResult.Token
        assertEquals("Hello", t1.text)
    }

    @Test
    fun `parseAnthropicSse ignores non-text_delta events`() {
        val sseData = """
            data: {"type":"message_start"}
            data: {"type":"content_block_start","index":0}
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}
            data: {"type":"content_block_stop"}
            data: {"type":"message_stop"}
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseAnthropicSse(reader, gson, channel)

        assertEquals(1, tokenCount)
    }

    @Test
    fun `parseAnthropicSse stops at message_stop`() {
        val sseData = """
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"before"}}
            data: {"type":"message_stop"}
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"after"}}
        """.trimIndent()

        val reader = BufferedReader(StringReader(sseData))
        val channel = Channel<StreamResult>(Channel.UNLIMITED)
        val tokenCount = SseParser.parseAnthropicSse(reader, gson, channel)

        assertEquals(1, tokenCount) // Only "before" — stops at message_stop
    }
}
