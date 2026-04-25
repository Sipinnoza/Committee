package com.znliang.committee.engine

import android.content.Context
import android.util.Log
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.engine.runtime.DynamicToolRegistry
import com.znliang.committee.domain.model.MicContext
import com.znliang.committee.engine.sse.SseParser
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Agent 池 — LLM 调用入口。
 *
 * Phase 0 重构：
 * - Flow<String> → Flow<StreamResult>（类型化错误，消除魔法字符串）
 * - Thread.sleep → delay（协程友好）
 * - SSE 解析循环提取到 SseParser（消除 ~200 行重复）
 *
 * Phase 1 重构：
 * - 删除 AgentRole 枚举依赖，所有调用统一走 callAgentStreamingByRoleId
 */
@Singleton
class AgentPool @Inject constructor(
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val gson: Gson,
    @Named("streaming") private val okHttp: OkHttpClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val toolRegistry: DynamicToolRegistry,
) : LlmClient {
    companion object {
        private const val TAG = "AgentPool"
        private const val MAX_TOOL_ROUNDS = 3
        private const val MAX_429_RETRIES = 3

        /** 智谱 GLM 原生 web_search tool — 无需外部 API，模型自动联网搜索 */
        private val ZAI_WEB_SEARCH_TOOL = mapOf(
            "type" to "web_search",
            "web_search" to mapOf("enable" to true),
        )

        /** 计费不足的错误码（Z.AI code=1113 等） — 重试无意义，直接上浮 */
        private val BILLING_ERROR_PATTERNS = listOf(
            "insufficient balance", "余额不足", "no resource package",
            "\"code\":\"1113\"", "\"code\": \"1113\"",
        )
    }

    private fun isBillingError(responseBody: String): Boolean {
        val lower = responseBody.lowercase()
        return BILLING_ERROR_PATTERNS.any { lower.contains(it.lowercase()) }
    }

    // ── 系统级流式调用（无 Agent 身份） ────────────────────────────

    override fun callSystemStreaming(config: LlmConfig, systemPrompt: String, userPrompt: String): Flow<StreamResult> = channelFlow {
        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "[SYS-STREAM] provider=${config.provider.displayName} model=${config.model}")

        val sys = systemPrompt.ifBlank { "你是一个AI助手。" }
        val user = userPrompt.ifBlank { "请执行" }
        val sysTools = if (config.provider == LlmProvider.ZAI) listOf(ZAI_WEB_SEARCH_TOOL) else emptyList()

        launch(Dispatchers.IO) {
            try {
                when (config.provider) {
                    LlmProvider.ANTHROPIC -> doAnthropicStream(channel, config, sys, user)
                    else                  -> doOpenAiStream(channel, config.baseUrl, config, sys, user, sysTools)
                }
            } catch (e: SseParser.BillingExhaustedException) {
                Log.e(TAG, "[SYS-STREAM] 计费错误: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.BILLING, e.message ?: "API余额不足"))
            } catch (e: SseParser.RateLimitExhaustedException) {
                Log.w(TAG, "[SYS-STREAM] 限速重试耗尽: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.RATE_LIMIT, e.message ?: "API限速，请稍后重试"))
            } catch (e: Exception) {
                Log.w(TAG, "[SYS-STREAM] 错误: ${e.javaClass.simpleName}: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.UNKNOWN, e.message ?: ""))
            } finally {
                channel.trySend(StreamResult.Done)
                close()
            }
        }
        awaitClose {}
    }

    // ── 流式调用 ────────────────────────────────────────────────────────

    /**
     * Role-ID based streaming — 唯一公开入口。
     * 使用 per-agent config（若设置），否则 fallback 到全局 config。
     */
    override fun callAgentStreamingByRoleId(
        roleId: String,
        context: MicContext,
        systemPromptOverride: String?,
        materials: List<MaterialData>,
    ): Flow<StreamResult> = channelFlow {
        val config = apiKeyProvider.getAgentConfigCached(roleId)
        val systemPrompt = systemPromptOverride ?: "你是一个AI助手。请根据你的角色职责协助讨论。"
        val userMessage = buildString {
            appendLine("标的：${context.subject}")
            if (context.task.isNotBlank()) appendLine("任务说明：${context.task}")
        }

        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "[STREAM] provider=${config.provider.displayName} model=${config.model} roleId=$roleId")

        val toolsSchema = toolRegistry.buildToolsSchema()
        val zaiWebSearch = if (config.provider == LlmProvider.ZAI) listOf(ZAI_WEB_SEARCH_TOOL) else emptyList()
        val allTools = toolsSchema + zaiWebSearch

        if (allTools.isEmpty() || config.provider == LlmProvider.ANTHROPIC) {
            // 无 skill 或 Anthropic（暂不支持 tool calling），走原始 SSE 流
            launch(Dispatchers.IO) {
                try {
                    when (config.provider) {
                        LlmProvider.ANTHROPIC -> doAnthropicStream(channel, config, systemPrompt, userMessage, materials)
                        else -> doOpenAiStream(channel, config.baseUrl, config, systemPrompt, userMessage, allTools, materials)
                    }
                } catch (e: SseParser.BillingExhaustedException) {
                    Log.e(TAG, "[STREAM] 计费错误: ${e.message}")
                    channel.trySend(StreamResult.Error(ErrorType.BILLING, e.message ?: "API余额不足，请充值后重试"))
                } catch (e: SseParser.RateLimitExhaustedException) {
                    Log.w(TAG, "[STREAM] 限速重试耗尽: ${e.message}")
                    channel.trySend(StreamResult.Error(ErrorType.RATE_LIMIT, e.message ?: "API限速，请稍后重试"))
                } catch (e: Exception) {
                    Log.w(TAG, "[STREAM] 错误: ${e.javaClass.simpleName}: ${e.message}")
                    channel.trySend(StreamResult.Error(ErrorType.NETWORK, "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"))
                } finally {
                    channel.trySend(StreamResult.Done)
                    close()
                }
            }
            awaitClose {}
            return@channelFlow
        }

        // ── Tool Calling 循环 ──────────────────────────────────
        launch(Dispatchers.IO) {
            try {
                val messages = mutableListOf<Map<String, Any>>(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userMessage),
                )

                var round = 0
                while (round < MAX_TOOL_ROUNDS) {
                    val (hasToolCalls, assistantContent, toolCalls) =
                        checkToolCalls(config, messages, allTools)

                    if (!hasToolCalls) {
                        doOpenAiStreamWithMessages(channel, config, messages)
                        break
                    }

                    Log.d(TAG, "[TOOL] 第${round + 1}轮，收到 ${toolCalls.size} 个 tool_call")
                    messages.add(mapOf(
                        "role" to "assistant",
                        "content" to (assistantContent ?: ""),
                        "tool_calls" to toolCalls,
                    ))

                    for (tc in toolCalls) {
                        @Suppress("UNCHECKED_CAST")
                        val func = tc["function"] as? Map<String, Any> ?: continue
                        val toolName = func["name"] as? String ?: continue
                        val arguments = func["arguments"] as? String ?: "{}"
                        val toolCallId = tc["id"] as? String ?: ("tc_${System.currentTimeMillis()}")

                        val result = toolRegistry.executeToolCall(toolName, arguments)
                        Log.d(TAG, "[TOOL] $toolName → ${result.take(100)}")
                        messages.add(mapOf(
                            "role" to "tool",
                            "tool_call_id" to toolCallId,
                            "content" to result.take(4000),
                        ))
                    }

                    val toolResults = messages.filter { it["role"] == "tool" }
                    val allEmpty = toolResults.isNotEmpty() && toolResults.all {
                        (it["content"] as? String)?.let { c ->
                            c.contains("无搜索结果") || c.contains("搜索失败") || c.isBlank()
                        } ?: true
                    }
                    if (allEmpty) {
                        messages.add(mapOf(
                            "role" to "user",
                            "content" to "搜索工具多次未能返回结果（可能网络问题）。请不要再调用搜索工具，直接基于你的已有知识给出分析。如果信息不足，请明确说明信息缺口。"
                        ))
                    }
                    round++
                }

                if (round >= MAX_TOOL_ROUNDS) {
                    doOpenAiStreamWithMessages(channel, config, messages)
                }
            } catch (e: SseParser.BillingExhaustedException) {
                Log.e(TAG, "[TOOL-LOOP] 计费错误: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.BILLING, e.message ?: "API余额不足，请充值后重试"))
            } catch (e: SseParser.RateLimitExhaustedException) {
                Log.w(TAG, "[TOOL-LOOP] 限速重试耗尽: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.RATE_LIMIT, e.message ?: "API限速，请稍后重试"))
            } catch (e: Exception) {
                Log.w(TAG, "[TOOL-LOOP] 错误: ${e.javaClass.simpleName}: ${e.message}")
                channel.trySend(StreamResult.Error(ErrorType.NETWORK, "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"))
            } finally {
                channel.trySend(StreamResult.Done)
                close()
            }
        }
        awaitClose {}
    }

    /**
     * 非流式请求，检测 LLM 是否返回 tool_calls。
     * 返回 (hasToolCalls, assistantContent, toolCalls)
     */
    private suspend fun checkToolCalls(
        config: LlmConfig,
        messages: List<Map<String, Any>>,
        toolsSchema: List<Map<String, Any>>,
    ): Triple<Boolean, String?, List<Map<String, Any>>> {
        val url = "${config.baseUrl}${config.provider.chatEndpoint}"
        val body = gson.toJson(mapOf(
            "model" to config.model,
            "messages" to messages,
            "max_tokens" to 2048,
            "stream" to false,
            "tools" to toolsSchema,
        ))

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return SseParser.withRetry(MAX_429_RETRIES, "checkToolCalls", ::isBillingError) {
            val response = okHttp.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 429) {
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    throw SseParser.RateLimitException(errBody)
                }
                if (resp.code in 500..599) {
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    throw SseParser.ServerErrorException(resp.code, "Tool check HTTP ${resp.code}: $errBody")
                }
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    throw RuntimeException("Tool check HTTP ${resp.code}: $errBody")
                }

                val responseText = resp.body?.string() ?: ""
                val json = gson.fromJson(responseText, Map::class.java) as? Map<*, *>
                val choices = json?.get("choices") as? List<Map<String, Any>> ?: return@withRetry Triple(false, null, emptyList())
                val choice = choices.firstOrNull() ?: return@withRetry Triple(false, null, emptyList())
                val message = choice["message"] as? Map<String, Any> ?: return@withRetry Triple(false, null, emptyList())

                val toolCalls = message["tool_calls"] as? List<Map<String, Any>>
                val content = message["content"] as? String

                if (toolCalls != null && toolCalls.isNotEmpty()) {
                    Triple(true, content, toolCalls)
                } else {
                    Triple(false, content, emptyList())
                }
            }
        }
    }

    // ── SSE 流式方法（委托给 SseParser）──────────────────────────────

    /**
     * 带完整 messages 历史（含 tool results）的流式输出。
     */
    private suspend fun doOpenAiStreamWithMessages(
        channel: SendChannel<StreamResult>,
        config: LlmConfig,
        messages: List<Map<String, Any>>,
    ) {
        val url = "${config.baseUrl}${config.provider.chatEndpoint}"
        val zaiWebSearch = if (config.provider == LlmProvider.ZAI) listOf(ZAI_WEB_SEARCH_TOOL) else emptyList()
        val bodyMap = mutableMapOf<String, Any>(
            "model" to config.model,
            "messages" to messages,
            "max_tokens" to 2048,
            "stream" to true,
        )
        if (zaiWebSearch.isNotEmpty()) bodyMap["tools"] = zaiWebSearch

        val body = gson.toJson(bodyMap)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        SseParser.withRetry(MAX_429_RETRIES, "SSE-FINAL", ::isBillingError) {
            val response = okHttp.newCall(request).execute()
            if (response.code == 429) {
                val errBody = response.use { it.body?.string()?.take(300) ?: "" }
                throw SseParser.RateLimitException(errBody)
            }
            if (!response.isSuccessful) {
                response.use { resp ->
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    if (resp.code in 500..599) {
                        throw SseParser.ServerErrorException(resp.code, "Stream HTTP ${resp.code}: $errBody")
                    }
                    throw RuntimeException("Stream HTTP ${resp.code}: $errBody")
                }
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                response.close()
                return@withRetry
            }
            try {
                val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)
                Log.i(TAG, "[SSE-FINAL] 资源已释放，共 $tokenCount 个 token")
            } finally {
                reader.close()
                response.close()
            }
        }
    }

    /**
     * Anthropic SSE 流式调用。
     */
    private suspend fun doAnthropicStream(
        channel: SendChannel<StreamResult>,
        config: LlmConfig,
        system: String,
        user: String,
        materials: List<MaterialData> = emptyList(),
    ) {
        val url = "${config.baseUrl.trimEnd('/')}/${LlmProvider.ANTHROPIC.chatEndpoint}"
        Log.d(TAG, "[SSE-Anthropic] 开始请求 $url model=${config.model}")

        val userContent = LlmContentBuilder.buildContent(user, materials, LlmProvider.ANTHROPIC)
        val bodyJson = gson.toJson(mapOf(
            "model"     to config.model,
            "max_tokens" to 2048,
            "system"    to system,
            "messages"  to listOf(mapOf("role" to "user", "content" to userContent)),
            "stream"    to true,
        ))

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        SseParser.withRetry(MAX_429_RETRIES, "SSE-Anthropic", ::isBillingError) {
            val response = okHttp.newCall(request).execute()
            Log.d(TAG, "[SSE-Anthropic] HTTP ${response.code} received")
            if (response.code == 429) {
                val errBody = response.use { it.body?.string()?.take(300) ?: "" }
                throw SseParser.RateLimitException(errBody)
            }
            if (!response.isSuccessful) {
                response.use { resp ->
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    Log.w(TAG, "[SSE-Anthropic] 错误响应: $errBody")
                    if (resp.code in 500..599) {
                        throw SseParser.ServerErrorException(resp.code, "HTTP ${resp.code}: $errBody")
                    }
                    throw RuntimeException("HTTP ${resp.code}: $errBody")
                }
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                response.close()
                return@withRetry
            }
            try {
                val tokenCount = SseParser.parseAnthropicSse(reader, gson, channel)
                Log.i(TAG, "[SSE-Anthropic] 资源已释放，共 $tokenCount 个 token")
            } finally {
                reader.close()
                response.close()
            }
        }
    }

    /**
     * OpenAI-compatible SSE 流式调用。
     */
    private suspend fun doOpenAiStream(
        channel: SendChannel<StreamResult>,
        baseUrl: String,
        config: LlmConfig,
        system: String,
        user: String,
        tools: List<Map<String, Any>> = emptyList(),
        materials: List<MaterialData> = emptyList(),
    ) {
        val url = "${baseUrl}${config.provider.chatEndpoint}"
        Log.d(TAG, "[SSE] 开始请求 $url model=${config.model}")
        val userContent = LlmContentBuilder.buildContent(user, materials, config.provider)
        val bodyMap = mutableMapOf<String, Any>(
            "model"      to config.model,
            "messages"   to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user",   "content" to userContent),
            ),
            "max_tokens" to 2048,
            "stream"     to true,
        )
        if (tools.isNotEmpty()) bodyMap["tools"] = tools

        val body = gson.toJson(bodyMap)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        SseParser.withRetry(MAX_429_RETRIES, "SSE", ::isBillingError) {
            val response = okHttp.newCall(request).execute()
            Log.d(TAG, "[SSE] HTTP ${response.code} received")
            if (response.code == 429) {
                val errBody = response.use { it.body?.string()?.take(300) ?: "" }
                throw SseParser.RateLimitException(errBody)
            }
            if (!response.isSuccessful) {
                response.use { resp ->
                    val errBody = resp.body?.string()?.take(300) ?: ""
                    if (resp.code in 500..599) {
                        throw SseParser.ServerErrorException(resp.code, "HTTP ${resp.code}: $errBody")
                    }
                    throw RuntimeException("HTTP ${resp.code}: $errBody")
                }
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                response.close()
                return@withRetry
            }
            try {
                val tokenCount = SseParser.parseOpenAiSse(reader, gson, channel)
                Log.i(TAG, "[SSE] 资源已释放，共 $tokenCount 个 token")
            } finally {
                reader.close()
                response.close()
            }
        }
    }

    // ── System Prompts ─────────────────────────────────────────────────────

    /** Get system prompt by role ID string — loads from file/assets */
    override fun getSystemPromptTextByRoleId(roleId: String): String? {
        // Try local override file first
        try {
            val localFile = java.io.File(appContext.filesDir, "prompts/role_${roleId}.txt")
            if (localFile.exists()) {
                val text = localFile.readText()
                if (text.isNotBlank()) return text.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Prompt] 读取本地文件失败: ${e.message}")
        }
        // Try assets
        try {
            val filename = "prompts/role_${roleId}.txt"
            val text = appContext.assets.open(filename).bufferedReader().use { it.readText() }
            if (text.isNotBlank()) return text.trim()
        } catch (_: Exception) {}
        return null
    }
}
