package com.znliang.committee.engine

import android.content.Context
import android.util.Log
import com.znliang.committee.di.DataStoreApiKeyProvider
import com.znliang.committee.engine.runtime.DynamicToolRegistry
import com.znliang.committee.domain.model.AgentRole
import com.znliang.committee.domain.model.MeetingState
import com.znliang.committee.domain.model.MicContext
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Agent 池
 *
 * 修复：
 * 1. Anthropic 流式从"全量返回后 chunked 模拟"改为真正的 SSE 流（OkHttp + content_block_delta）。
 * 2. buildUserMessage 对旧发言做摘要压缩，最近 4 条全量、更早的截断到 150 字，
 *    防止随轮次增加 token 爆炸（原代码注入全量历史，14 轮后单次调用可达数万 token）。
 * 3. validate_entry 改为结构化标记判断（策略师输出 【PASS】/【REJECT】），
 *    原字符串匹配"拒绝"/"不通过"在 LLM 反驳空头理由时会误判。
 */
@Singleton
class AgentPool @Inject constructor(
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val gson: Gson,
    @Named("streaming") private val okHttp: OkHttpClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val toolRegistry: DynamicToolRegistry?,
) {
    companion object {
        private const val TAG = "AgentPool"
        private const val MAX_TOOL_ROUNDS = 3

        /** 智谱 GLM 原生 web_search tool — 无需外部 API，模型自动联网搜索 */
        private val ZAI_WEB_SEARCH_TOOL = mapOf(
            "type" to "web_search",
            "web_search" to mapOf("enable" to true),
        )
    }

    // ── 系统级流式调用（无 Agent 身份） ────────────────────────────

    /**
     * 系统级 LLM 调用。不关联 AgentRole，直接使用传入的 config。
     * 用于 Supervisor 结束判断、点评、评级、摘要等。
     */
    fun callSystemStreaming(config: LlmConfig, systemPrompt: String, userPrompt: String): Flow<String> = channelFlow {
        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "[SYS-STREAM] provider=${config.provider.displayName} model=${config.model}")

        val sys = systemPrompt.ifBlank { "你是一个AI助手。" }
        val user = userPrompt.ifBlank { "请执行" }
        val sysTools = if (config.provider == LlmProvider.ZAI) listOf(ZAI_WEB_SEARCH_TOOL) else emptyList()

        launch(Dispatchers.IO) {
            try {
                when (config.provider) {
                    LlmProvider.ANTHROPIC -> collectAnthropicStream(channel, config, sys, user)
                    else                  -> collectOpenAiStream(channel, config.baseUrl, config, sys, user, sysTools)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[SYS-STREAM] 错误: ${e.javaClass.simpleName}: ${e.message}")
                channel.trySend("")
            } finally {
                close()
            }
        }
        awaitClose {}
    }

    // ── 流式调用 ────────────────────────────────────────────────────────

    /** Role-ID based streaming — delegates to AgentRole-based version */
    fun callAgentStreamingByRoleId(
        roleId: String,
        context: MicContext,
        systemPromptOverride: String? = null,
        materials: List<MaterialData> = emptyList(),
    ): Flow<String> {
        val agentRole = AgentRole.fromId(roleId) ?: AgentRole.SUPERVISOR
        return callAgentStreaming(agentRole, context, systemPromptOverride, materials)
    }

    // ── 带 Tool Calling 的流式调用 ───────────────────────────────

    /**
     * 先发非流式请求（带 tools schema），检测是否有 tool_calls。
     * 如果有 → 执行 tool → 结果加入 messages → 再请求（可能多轮）。
     * 最后无 tool_calls 时 → 发流式请求拿最终文本。
     */
    fun callAgentStreaming(role: AgentRole, context: MicContext, systemPromptOverride: String? = null, materials: List<MaterialData> = emptyList()): Flow<String> = channelFlow {
        val config = apiKeyProvider.getAgentConfig(role.id)
        val systemPrompt = systemPromptOverride ?: buildSystemPrompt(role, context)
        val userMessage = if (systemPromptOverride != null) {
            buildString {
                appendLine("标的：${context.subject}")
                if (context.task.isNotBlank()) appendLine("任务说明：${context.task}")
            }
        } else {
            buildUserMessage(context)
        }

        Log.d(TAG, "══════════════════════════════════════════════════")
        Log.d(TAG, "[STREAM] provider=${config.provider.displayName} model=${config.model}")
        Log.d(TAG, "[STREAM] role=${role.displayName} subject=${context.subject}")

        val toolsSchema = toolRegistry?.buildToolsSchema() ?: emptyList()
        val zaiWebSearch = if (config.provider == LlmProvider.ZAI) listOf(ZAI_WEB_SEARCH_TOOL) else emptyList()
        val allTools = toolsSchema + zaiWebSearch

        if (allTools.isEmpty() || config.provider == LlmProvider.ANTHROPIC) {
            // 无 skill 或 Anthropic（暂不支持），走原始 SSE 流
            launch(Dispatchers.IO) {
                try {
                    when (config.provider) {
                        LlmProvider.ANTHROPIC -> collectAnthropicStream(channel, config, systemPrompt, userMessage, materials)
                        else -> collectOpenAiStream(channel, config.baseUrl, config, systemPrompt, userMessage, allTools, materials)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[STREAM] 错误: ${e.javaClass.simpleName}: ${e.message}")
                    channel.trySend("【请求失败】${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
                } finally { close() }
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
                        // 无 tool calls，走流式输出最终响应
                        // assistantContent 是 LLM 可能已经生成的前缀文本（非流式模式下不适用）
                        collectOpenAiStreamWithMessages(channel, config, messages)
                        break
                    }

                    // 有 tool_calls → 执行
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

                        val result = toolRegistry!!.executeToolCall(toolName, arguments)
                        Log.d(TAG, "[TOOL] $toolName → ${result.take(100)}")
                        messages.add(mapOf(
                            "role" to "tool",
                            "tool_call_id" to toolCallId,
                            "content" to result.take(4000),
                        ))
                    }

                    // 🔥 搜索全空时注入fallback提示，避免无意义重试
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

                // 超过最大轮次也要输出
                if (round >= MAX_TOOL_ROUNDS) {
                    collectOpenAiStreamWithMessages(channel, config, messages)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[TOOL-LOOP] 错误: ${e.javaClass.simpleName}: ${e.message}")
                channel.trySend("【请求失败】${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            } finally { close() }
        }
        awaitClose {}
    }

    /**
     * 非流式请求，检测 LLM 是否返回 tool_calls。
     * 返回 (hasToolCalls, assistantContent, toolCalls)
     */
    private fun checkToolCalls(
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

        val client = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(300) ?: ""
            throw RuntimeException("Tool check HTTP ${response.code}: $errBody")
        }

        val responseText = response.body?.string() ?: ""
        val json = gson.fromJson(responseText, Map::class.java) as? Map<*, *>
        val choices = json?.get("choices") as? List<Map<String, Any>> ?: return Triple(false, null, emptyList())
        val choice = choices.firstOrNull() ?: return Triple(false, null, emptyList())
        val message = choice["message"] as? Map<String, Any> ?: return Triple(false, null, emptyList())

        val toolCalls = message["tool_calls"] as? List<Map<String, Any>>
        val content = message["content"] as? String

        if (toolCalls != null && toolCalls.isNotEmpty()) {
            return Triple(true, content, toolCalls)
        }
        return Triple(false, content, emptyList())
    }

    /**
     * 带完整 messages 历史（含 tool results）的流式输出。
     */
    private fun collectOpenAiStreamWithMessages(
        channel: SendChannel<String>,
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

        val sseClient = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val response = sseClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(300) ?: ""
            throw RuntimeException("Stream HTTP ${response.code}: $errBody")
        }

        val reader = response.body?.byteStream()?.bufferedReader() ?: return
        var tokenCount = 0
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "[SSE-FINAL] 读超时，已 $tokenCount token")
                    break
                } ?: break

                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue

                try {
                    @Suppress("UNCHECKED_CAST")
                    val respJson = gson.fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val choices = respJson["choices"] as? List<Map<String, Any>> ?: continue
                    val delta = choices.firstOrNull()?.get("delta") as? Map<String, Any> ?: continue
                    val content = delta["content"] as? String ?: continue
                    if (content.isNotEmpty()) {
                        channel.trySend(content)
                        tokenCount++
                    }
                } catch (_: Exception) { }
            }
        } finally {
            reader.close()
            response.close()
        }
    }

    // ── 原始流式调用（保留） ────────────────────────────────────

    // ── Anthropic SSE（真正流式）─────────────────────────────────────────────

    /**
     * 通过 OkHttp 手动解析 Anthropic SSE 流。
     *
     * 关键事件格式：
     *   event: content_block_delta
     *   data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     *
     * 终止事件：
     *   event: message_stop
     *   data: {"type":"message_stop"}
     */
    private fun collectAnthropicStream(
        channel: SendChannel<String>,
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

        val sseClient = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val response = sseClient.newCall(request).execute()
        Log.d(TAG, "[SSE-Anthropic] HTTP ${response.code} received")
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(300) ?: ""
            Log.w(TAG, "[SSE-Anthropic] 错误响应: $errBody")
            throw RuntimeException("HTTP ${response.code}: $errBody")
        }

        val reader = response.body?.byteStream()?.bufferedReader() ?: return
        var tokenCount = 0
        try {
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
                                    channel.trySend(text)
                                    tokenCount++
                                }
                            }
                        }
                        "message_stop" -> {
                            Log.d(TAG, "[SSE-Anthropic] message_stop，共 $tokenCount 个 token")
                            break
                        }
                    }
                } catch (_: Exception) {
                    // 忽略格式错误的 SSE 块
                }
            }
        } finally {
            reader.close()
            response.close()
            Log.i(TAG, "[SSE-Anthropic] 资源已释放")
        }
    }

    // ── OpenAI-compatible SSE ────────────────────────────────────────────────

    private fun collectOpenAiStream(
        channel: SendChannel<String>,
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

        val sseClient = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val response = sseClient.newCall(request).execute()
        Log.d(TAG, "[SSE] HTTP ${response.code} received")
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(300) ?: ""
            throw RuntimeException("HTTP ${response.code}: $errBody")
        }

        val reader = response.body?.byteStream()?.bufferedReader() ?: return
        var tokenCount = 0
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "[SSE] 读超时，已收到 $tokenCount 个 token，视为流结束")
                    break
                } ?: break

                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue

                try {
                    @Suppress("UNCHECKED_CAST")
                    val json    = gson.fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val choices = json["choices"] as? List<Map<String, Any>> ?: continue
                    val delta   = choices.firstOrNull()?.get("delta") as? Map<String, Any> ?: continue
                    val content = delta["content"] as? String ?: continue
                    if (content.isNotEmpty()) {
                        channel.trySend(content)
                        tokenCount++
                    }
                } catch (_: Exception) { }
            }
        } finally {
            reader.close()
            response.close()
            Log.i(TAG, "[SSE] 资源已释放，共 $tokenCount 个 token")
        }
    }

    // ── System Prompts ─────────────────────────────────────────────────────

    fun getSystemPromptText(role: AgentRole): String = buildSystemPrompt(role, EMPTY_CONTEXT)

    /** Get system prompt by role ID string (for generic presets) */
    fun getSystemPromptTextByRoleId(roleId: String): String? {
        val agentRole = AgentRole.fromId(roleId) ?: return null
        return buildSystemPrompt(agentRole, EMPTY_CONTEXT)
    }

    private val EMPTY_CONTEXT = MicContext(
        traceId = "", causedBy = "", round = 0,
        phase = MeetingState.IDLE, subject = "",
        agentRoleId = "", task = "",
    )

    private fun buildSystemPrompt(role: AgentRole, ctx: MicContext): String {
        try {
            val localFile = java.io.File(appContext.filesDir, "prompts/${role.systemPromptKey}.txt")
            if (localFile.exists()) {
                val text = localFile.readText()
                if (text.isNotBlank()) return text.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Prompt] 读取本地文件失败: ${e.message}")
        }
        try {
            val filename = "prompts/${role.systemPromptKey}.txt"
            val text = appContext.assets.open(filename).bufferedReader().use { it.readText() }
            if (text.isNotBlank()) return text.trim()
        } catch (_: Exception) {}
        return buildInbuiltPrompt(role)
    }

    private fun buildInbuiltPrompt(role: AgentRole): String = when (role) {
        AgentRole.ANALYST -> """
            你是**分析师**（Bull Case，看多立场）。
            职责：构建多头论据、估值框架、回顾前次预测准确性。
            核心原则：
            - 始终寻找价值被低估的证据
            - 提供具体估值区间（PE/PB/DCF）
            - 引用数据时注明来源和时间
            - 如与对手达成共识，明确说出【CONSENSUS REACHED】
            会议目标：年化收益 ≥ 10%，最大回撤 ≤ 15%，基准：上证综指
        """.trimIndent()

        AgentRole.RISK_OFFICER -> """
            你是**风险官**（Bear Case，看空立场）。
            职责：构建空头论据、识别风险日历、质疑多头假设。
            核心原则：
            - 寻找估值泡沫、基本面恶化的证据
            - 量化下行风险（最坏情形/压力测试）
            - 识别黑天鹅事件、政策风险
            - 如与对手达成共识，明确说出【CONSENSUS REACHED】
            风险约束：最大回撤 ≤ 15%
        """.trimIndent()

        // 修复：补充结构化入场评估输出格式
        AgentRole.STRATEGIST -> """
            你是**策略师**（中立/框架立场）。
            职责：Top-down 宏观策略框架、入场评估、跨会议一致性检查。
            核心原则：
            - 从宏观（利率、汇率、政策）到微观（行业、公司）自上而下分析
            - 检查本次决策是否与前序决议冲突
            - 评估当前市场环境是否适合入场
            - 如各方达成共识，明确说出【CONSENSUS REACHED】
            入场评估决定（validate_entry 任务时必须输出）：
            - 通过：在回复末尾单独一行输出 【PASS】
            - 拒绝：在回复末尾单独一行输出 【REJECT】，并说明拒绝原因
        """.trimIndent()

        AgentRole.EXECUTOR -> """
            你是**执行员**（方案立场）。
            职责：制定执行方案、发布评级、追踪执行进度。
            评级体系（6级）：Buy → Overweight → Hold+ → Hold → Underweight → Sell
            执行方案要素：
            - 建仓/减仓价格区间
            - 仓位比例（占组合%）
            - 止损线
            - 目标价与时间框架
            - 可证伪条件（什么情况下改变判断）
            发布评级时格式：【最终评级】Buy/Overweight/Hold/Hold+/Underweight/Sell
        """.trimIndent()

        AgentRole.INTEL -> """
            你是**情报员**（事实立场）。
            职责：收集和整理标的基础情报，提供增量信息推送。
            情报清单：
            - 最新财务数据（营收、利润、现金流）
            - 重大公告与监管动态
            - 行业供需变化
            - 主要竞争对手动态
            - 分析师一致预期
            完成后发出：我的情报准备完毕。
        """.trimIndent()

        AgentRole.SUPERVISOR -> """
            你是**监督员**（评判立场）。
            职责：仲裁争议、撰写会议纪要、追踪执行纪律。
            仲裁原则：
            - 基于证据质量而非立场倾向裁决
            - 明确指出哪方论据更具说服力，理由是什么
            - 强制推进会议避免无限循环
            纪要格式：
            1. 会议概要
            2. 多空双方核心论点
            3. 最终评级与执行方案
            4. 需追踪的执行事项
            5. 下次会议议题
        """.trimIndent()
    }

    /**
     * 修复：对旧发言做摘要压缩，防止 token 随轮次爆炸。
     *
     * 简化版：runtime 层通过 board.contextForAgent 管理 context。
     *
     * 原代码注入全量历史：14 轮（8辩+6评估）× 平均 800 字 ≈ 11200 字/次调用，
     *        到后期每次 API 调用 context 可超过 10k token。
     */
    private fun buildUserMessage(ctx: MicContext): String {
        val phaseDesc = when (ctx.phase) {
            MeetingState.VALIDATING          -> "【入场评估】请评估本次会议的合规性与标的适格性"
            MeetingState.PREPPING            -> "【准备阶段】请完成你的准备工作"
            MeetingState.PHASE1_DEBATE       -> "【辩论 第${ctx.round}轮】请发表你的观点"
            MeetingState.PHASE2_ASSESSMENT   -> "【风险评估 第${ctx.round}轮】请基于已有讨论进行评估"
            MeetingState.FINAL_RATING        -> "【发布评级】请基于全部讨论发布最终投资评级"
            MeetingState.COMPLETED           -> "【撰写纪要】请撰写本次会议完整纪要"
            else                             -> "【${ctx.phase.displayName}】请执行你的职责"
        }
        return buildString {
            appendLine(phaseDesc)
            appendLine("标的：${ctx.subject}")
            if (ctx.task.isNotBlank()) appendLine("任务说明：${ctx.task}")
        }
    }

}