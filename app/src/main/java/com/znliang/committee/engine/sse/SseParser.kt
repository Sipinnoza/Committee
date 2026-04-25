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
     * 中途断流会向 channel 发送 Error 而非静默丢失。
     */
    fun parseOpenAiSse(
        reader: java.io.BufferedReader,
        gson: Gson,
        channel: SendChannel<StreamResult>,
    ): Int {
        var tokenCount = 0
        while (true) {
            val line: String
            try {
                val read = reader.readLine()
                if (read == null) {
                    // 连接被服务端关闭（可能是限速断流）
                    if (tokenCount == 0) {
                        channel.trySend(StreamResult.Error(ErrorType.NETWORK, "SSE 连接被关闭，未收到任何数据"))
                    } else if (tokenCount < 5) {
                        // 收到极少 token 后断开 — 很可能是限速截断
                        channel.trySend(StreamResult.Error(ErrorType.RATE_LIMIT, "SSE 连接被提前关闭（仅收到 $tokenCount token），可能被限速"))
                    }
                    break
                }
                line = read
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "[SSE-OpenAI] 读超时，已收到 $tokenCount 个 token")
                if (tokenCount == 0) {
                    channel.trySend(StreamResult.Error(ErrorType.NETWORK, "SSE 读超时，未收到任何数据"))
                }
                break
            }

            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isBlank()) continue

            try {
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                // 检查 SSE 内嵌错误 (部分 API 在 data 中返回 error 字段)
                @Suppress("UNCHECKED_CAST")
                val error = json["error"] as? Map<String, Any>
                if (error != null) {
                    val errMsg = error["message"] as? String ?: "SSE 内嵌错误"
                    Log.w(TAG, "[SSE-OpenAI] SSE error event: $errMsg")
                    channel.trySend(StreamResult.Error(ErrorType.NETWORK, errMsg))
                    break
                }
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
            val line: String
            try {
                val read = reader.readLine()
                if (read == null) {
                    if (tokenCount == 0) {
                        channel.trySend(StreamResult.Error(ErrorType.NETWORK, "SSE 连接被关闭"))
                    }
                    break
                }
                line = read
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "[SSE-Anthropic] 读超时，已收到 $tokenCount 个 token")
                if (tokenCount == 0) {
                    channel.trySend(StreamResult.Error(ErrorType.NETWORK, "SSE 读超时"))
                }
                break
            }

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
                    "error" -> {
                        @Suppress("UNCHECKED_CAST")
                        val error = json["error"] as? Map<String, Any>
                        val errMsg = error?.get("message") as? String ?: "Anthropic SSE 错误"
                        channel.trySend(StreamResult.Error(ErrorType.NETWORK, errMsg))
                        break
                    }
                }
            } catch (_: Exception) { }
        }
        return tokenCount
    }

    /**
     * 通用重试 + 退避 + 抖动 + 计费检测。
     *
     * 支持重试：429 (Rate Limit) + 5xx (Server Error) + 连接异常
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
                Log.w(TAG, "[$tag] 429 rate limit, attempt ${attempt + 1}/${maxRetries + 1}")
                lastException = RateLimitExhaustedException("HTTP 429 重试耗尽: $errBody")
                val backoff = minOf(1000L * (1L shl attempt), 8000L)
                val jitter = (Math.random() * backoff * 0.3).toLong() // 30% 抖动
                delay(backoff + jitter)
            } catch (e: ServerErrorException) {
                // 5xx 服务端错误 — 可重试
                Log.w(TAG, "[$tag] ${e.statusCode} server error, attempt ${attempt + 1}/${maxRetries + 1}: ${e.message}")
                lastException = e
                val backoff = minOf(2000L * (1L shl attempt), 15000L) // 5xx 退避更长
                val jitter = (Math.random() * backoff * 0.3).toLong()
                delay(backoff + jitter)
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "[$tag] 连接超时, attempt ${attempt + 1}/${maxRetries + 1}")
                lastException = e
                delay(minOf(1000L * (1L shl attempt), 8000L))
            } catch (e: java.io.IOException) {
                // 连接被重置、ECONNRESET 等
                Log.w(TAG, "[$tag] IO 异常: ${e.javaClass.simpleName}, attempt ${attempt + 1}/${maxRetries + 1}")
                lastException = e
                delay(minOf(1000L * (1L shl attempt), 8000L))
            }
        }
        throw lastException ?: RuntimeException("$tag failed after ${maxRetries + 1} attempts")
    }

    /** 429 速率限制异常 — 内部使用 */
    class RateLimitException(val responseBody: String) : RuntimeException("HTTP 429")

    /** 429 重试耗尽 — 区分于普通 RuntimeException，用于上层映射到 RATE_LIMIT ErrorType */
    class RateLimitExhaustedException(message: String) : RuntimeException(message)

    /** 5xx 服务端错误 — 可重试 */
    class ServerErrorException(val statusCode: Int, message: String) : RuntimeException(message)

    /** 计费不足异常 — 不可重试 */
    class BillingExhaustedException(message: String) : RuntimeException(message)
}
