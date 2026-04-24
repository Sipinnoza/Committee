package com.znliang.committee.engine.sse

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import com.znliang.committee.engine.StreamResult
import com.znliang.committee.engine.ErrorType

/**
 * 共享 SSE 解析器 — 消除 AgentPool 中三个几乎相同的 SSE 解析循环。
 */
object SseParser {
    private const val TAG = "SseParser"

    /**
     * 解析 OpenAI-compatible SSE 流。
     * 共享逻辑：`data:` 前缀解析、`[DONE]` 终止、`choices[0].delta.content` 提取。
     */
    fun parseOpenAiSse(
        reader: java.io.BufferedReader,
        gson: Gson,
        channel: SendChannel<StreamResult>,
    ): Int {
        var tokenCount = 0
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "[SSE-OpenAI] 读超时，已收到 $tokenCount 个 token")
                break
            } ?: break

            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isBlank()) continue

            try {
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                @Suppress("UNCHECKED_CAST")
                val choices = json["choices"] as? List<Map<String, Any>> ?: continue
                val delta = choices.firstOrNull()?.get("delta") as? Map<String, Any> ?: continue
                val content = delta["content"] as? String ?: continue
                if (content.isNotEmpty()) {
                    channel.trySend(StreamResult.Token(content))
                    tokenCount++
                }
            } catch (_: Exception) { }
        }
        return tokenCount
    }

    /**
     * 解析 Anthropic SSE 流。
     * 关键事件：content_block_delta → delta.text、message_stop → 终止。
     */
    fun parseAnthropicSse(
        reader: java.io.BufferedReader,
        gson: Gson,
        channel: SendChannel<StreamResult>,
    ): Int {
        var tokenCount = 0
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "[SSE-Anthropic] 读超时，已收到 $tokenCount 个 token")
                break
            } ?: break

            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trim()
            if (data.isBlank()) continue

            try {
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(data, Map::class.java) as? Map<String, Any> ?: continue
                when (json["type"] as? String) {
                    "content_block_delta" -> {
                        @Suppress("UNCHECKED_CAST")
                        val delta = json["delta"] as? Map<String, Any> ?: continue
                        if (delta["type"] == "text_delta") {
                            val text = delta["text"] as? String ?: continue
                            if (text.isNotEmpty()) {
                                channel.trySend(StreamResult.Token(text))
                                tokenCount++
                            }
                        }
                    }
                    "message_stop" -> {
                        Log.d(TAG, "[SSE-Anthropic] message_stop，共 $tokenCount 个 token")
                        break
                    }
                }
            } catch (_: Exception) { }
        }
        return tokenCount
    }

    /**
     * 通用重试 + 退避 + 计费检测。
     *
     * @param maxRetries 最大重试次数
     * @param isBillingError 判断 429 响应体是否为计费错误
     * @param block 执行 HTTP 请求并解析 SSE 的闭包
     */
    suspend fun <T> withRetry(
        maxRetries: Int = 3,
        tag: String = "SSE",
        isBillingError: (String) -> Boolean,
        block: suspend (attempt: Int) -> T,
    ): T {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block(attempt)
            } catch (e: RateLimitException) {
                val errBody = e.responseBody
                if (isBillingError(errBody)) {
                    throw BillingExhaustedException("【API余额不足】$errBody")
                }
                Log.w(TAG, "[$tag] 429 rate limit, attempt ${attempt + 1}/$maxRetries")
                lastException = RuntimeException("HTTP 429 (rate limit): $errBody")
                delay(minOf(1000L * (1L shl attempt), 8000L))
            }
        }
        throw lastException ?: RuntimeException("$tag failed after $maxRetries retries")
    }

    /** 429 速率限制异常 — 内部使用 */
    class RateLimitException(val responseBody: String) : RuntimeException("HTTP 429")

    /** 计费不足异常 — 不可重试 */
    class BillingExhaustedException(message: String) : RuntimeException(message)
}
