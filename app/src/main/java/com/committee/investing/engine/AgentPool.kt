package com.committee.investing.engine

import android.content.Context
import android.util.Log
import com.committee.investing.data.remote.AnthropicApiService
import com.committee.investing.data.remote.AnthropicMessage
import com.committee.investing.data.remote.AnthropicRequest
import com.committee.investing.data.remote.OpenAiApiService
import com.committee.investing.data.remote.OpenAiMessage
import com.committee.investing.data.remote.OpenAiRequest
import com.committee.investing.di.DataStoreApiKeyProvider
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.domain.model.MeetingState
import com.committee.investing.domain.model.MicContext
import com.google.gson.Gson
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Agent 池 — 规格文档 §9.4
 * Agent 不再是独立进程，而是函数调用。
 * 每个角色持有一个 System Prompt，通过 LLM API 产出内容。
 */
@Singleton
class AgentPool @Inject constructor(
    private val anthropicApi: AnthropicApiService,
    @Named("deepseek") private val deepSeekApi: OpenAiApiService,
    @Named("kimi") private val kimiApi: OpenAiApiService,
    private val apiKeyProvider: DataStoreApiKeyProvider,
    private val gson: Gson,
    private val okHttp: OkHttpClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) {
    companion object {
        private const val TAG = "AgentPool"
    }

    /**
     * 给 Agent 发话筒，等待 LLM 响应
     * 规格文档 §7.1 on_mic(agent_id, context) -> dict
     */
    suspend fun callAgent(role: AgentRole, context: MicContext): AgentResponse {
        val config = apiKeyProvider.getAgentConfig(role.id)
        val systemPrompt = buildSystemPrompt(role, context)
        val userMessage = buildUserMessage(context)

        Log.e(TAG, "══════════════════════════════════════════════════")
        Log.e(TAG, "[REQUEST] provider=${config.provider.displayName} model=${config.model}")
        Log.e(TAG, "[REQUEST] baseUrl=${config.baseUrl}")
        Log.e(TAG, "[REQUEST] role=${role.displayName} subject=${context.subject}")
        Log.e(TAG, "[REQUEST] apiKey=${config.apiKey.take(8)}...${config.apiKey.takeLast(4)}")
        Log.e(TAG, "[REQUEST] systemPrompt (${systemPrompt.length} chars)")
        Log.e(TAG, "[REQUEST] userMessage (${userMessage.length} chars): ${userMessage.take(200)}")

        val startTime = System.currentTimeMillis()

        val text = try {
            when (config.provider) {
                LlmProvider.ANTHROPIC -> callAnthropic(config, systemPrompt, userMessage)
                LlmProvider.DEEPSEEK -> callOpenAiCompatible(deepSeekApi, config, systemPrompt, userMessage)
                LlmProvider.KIMI -> callOpenAiCompatible(kimiApi, config, systemPrompt, userMessage)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "[ERROR] elapsed=${elapsed}ms")
            if (e is retrofit2.HttpException) {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string()?.take(500)
                Log.e(TAG, "[ERROR] HTTP $code body=$errorBody")
            }
            throw e
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.e(TAG, "[RESPONSE] ${elapsed}ms | ${text.length} chars")
        Log.e(TAG, "[RESPONSE] content preview: ${text.take(300)}")
        Log.e(TAG, "══════════════════════════════════════════════════")

        return AgentResponse(
            role = role,
            content = text,
            summary = extractSummary(text),
            hasConsensus = text.contains("【CONSENSUS REACHED】"),
            rating = extractRating(text),
        )
    }

    /**
     * 流式调用 Agent — 返回 Flow<String>，每个 emit 是一个 delta token
     * 使用 OkHttp SSE 手动解析，无需额外依赖
     */
    fun callAgentStreaming(role: AgentRole, context: MicContext): Flow<String> = channelFlow {
        val config = apiKeyProvider.getAgentConfig(role.id)
        val systemPrompt = buildSystemPrompt(role, context)
        val userMessage = buildUserMessage(context)

        Log.e(TAG, "══════════════════════════════════════════════════")
        Log.e(TAG, "[STREAM] provider=${config.provider.displayName} model=${config.model}")
        Log.e(TAG, "[STREAM] role=${role.displayName} subject=${context.subject}")

        launch(Dispatchers.IO) {
            try {
                when (config.provider) {
                    LlmProvider.ANTHROPIC -> {
                        val text = callAnthropic(config, systemPrompt, userMessage)
                        text.chunked(4).forEach { chunk -> channel.trySend(chunk) }
                    }
                    else -> {
                        // DEEPSEEK / KIMI / 其他 OpenAI-compatible
                        collectOpenAiStream(channel, config.baseUrl, config, systemPrompt, userMessage)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[STREAM] 错误: ${e.javaClass.simpleName}: ${e.message}")
                channel.trySend("【请求失败】${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            } finally {
                close()
            }
        }

        awaitClose {}
    }

    /**
     * OkHttp SSE 手动解析 OpenAI-compatible /v1/chat/completions stream
     * 格式: data: {"choices":[{"delta":{"content":"xxx"}}]}\n\n
     * 结束: data: [DONE]\n\n
     */
    private fun collectOpenAiStream(
        channel: SendChannel<String>,
        baseUrl: String,
        config: LlmConfig,
        system: String,
        user: String,
    ) {
        val url = "${baseUrl}${config.provider.chatEndpoint}"
        Log.e(TAG, "[SSE] 开始请求 $url model=${config.model}")
        val body = gson.toJson(mapOf(
            "model" to config.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            "max_tokens" to 2048,
            "stream" to true,
        ))

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        // SSE 专用 client：15s 读超时，防止 readLine() 永远阻塞
        val sseClient = okHttp.newBuilder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = sseClient.newCall(request).execute()
        Log.e(TAG, "[SSE] HTTP ${response.code} received")
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(300) ?: ""
            Log.e(TAG, "[SSE] 错误响应: $errBody")
            throw RuntimeException("HTTP ${response.code}: $errBody")
        }

        val reader = response.body?.byteStream()?.bufferedReader() ?: return
        var tokenCount = 0
        try {
            var line: String?
            while (true) {
                try {
                    line = reader.readLine()
                } catch (e: java.net.SocketTimeoutException) {
                    // 读超时 = 服务端不再发数据，视为流结束
                    Log.e(TAG, "[SSE] 读超时，已收到 $tokenCount 个 token，视为流结束")
                    break
                }
                if (line == null) {
                    Log.e(TAG, "[SSE] 连接关闭，已收到 $tokenCount 个 token")
                    break
                }
                line = line.trim()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    Log.e(TAG, "[SSE] 收到 [DONE]，共 $tokenCount 个 token")
                    break
                }
                if (data.isBlank()) continue
                try {
                    val json = gson.fromJson(data, Map::class.java) as? Map<*, *> ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val choices = json["choices"] as? List<Map<String, Any>> ?: continue
                    val delta = choices.firstOrNull()?.get("delta") as? Map<String, Any> ?: continue
                    val content = delta["content"] as? String ?: continue
                    if (content.isNotEmpty()) {
                        channel.trySend(content)
                        tokenCount++
                    }
                } catch (_: Exception) {
                    // skip malformed JSON chunks
                }
            }
        } finally {
            reader.close()
            response.close()
            Log.e(TAG, "[SSE] 资源已释放")
        }
    }

    private suspend fun callAnthropic(config: LlmConfig, system: String, user: String): String {
        val request = AnthropicRequest(
            model = config.model,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = user)),
            maxTokens = 2048,
        )
        Log.e(TAG, "[Anthropic] POST v1/messages model=${config.model} maxTokens=2048")

        val response = anthropicApi.createMessage(
            apiKey = config.apiKey,
            request = request,
        )

        Log.e(TAG, "[Anthropic] response.id=${response.id} usage=in=${response.usage.inputTokens} out=${response.usage.outputTokens}")
        return response.text
    }

    private suspend fun callOpenAiCompatible(
        api: OpenAiApiService,
        config: LlmConfig,
        system: String,
        user: String,
    ): String {
        val request = OpenAiRequest(
            model = config.model,
            messages = listOf(
                OpenAiMessage(role = "system", content = system),
                OpenAiMessage(role = "user", content = user),
            ),
            maxTokens = 2048,
        )
        Log.e(TAG, "[OpenAI-compat/${config.provider.id}] POST ${config.provider.chatEndpoint} model=${config.model}")

        val response = api.createChatCompletion(
            path = config.provider.chatEndpoint,
            authorization = "Bearer ${config.apiKey}",
            request = request,
        )

        Log.e(TAG, "[OpenAI-compat] response.id=${response.id} model=${response.model} finishReason=${response.choices.firstOrNull()?.finishReason}")
        response.usage?.let { u ->
            Log.e(TAG, "[OpenAI-compat] usage prompt=${u.promptTokens} completion=${u.completionTokens} total=${u.totalTokens}")
        }
        return response.text
    }

    // ── System Prompts ─────────────────────────────────────────────────────

    /**
     * Public accessor for system prompt text (for agent config display)
     */
    fun getSystemPromptText(role: AgentRole): String = buildSystemPrompt(role, EMPTY_CONTEXT)

    private val EMPTY_CONTEXT = MicContext(
        traceId = "", causedBy = "", round = 0,
        phase = MeetingState.IDLE, subject = "",
        agentRole = AgentRole.ANALYST, task = "",
    )

    private fun buildSystemPrompt(role: AgentRole, ctx: MicContext): String {
        // 1) filesDir/prompts/{key}.txt — 用户自定义（最高优先级，可随时替换）
        try {
            val localFile = java.io.File(appContext.filesDir, "prompts/${role.systemPromptKey}.txt")
            if (localFile.exists()) {
                val text = localFile.readText()
                if (text.isNotBlank()) {
                    Log.e(TAG, "[Prompt] 从本地文件加载了 ${role.displayName} 的 prompt")
                    return text.trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Prompt] 读取本地文件失败: ${e.message}")
        }

        // 2) assets/prompts/{key}.txt — 随 APK 分发
        try {
            val filename = "prompts/${role.systemPromptKey}.txt"
            val text = appContext.assets.open(filename).bufferedReader().use { it.readText() }
            if (text.isNotBlank()) {
                Log.e(TAG, "[Prompt] 从 assets/$filename 加载了 ${role.displayName} 的 prompt")
                return text.trim()
            }
        } catch (_: Exception) {}

        // 3) 硬编码默认值
        return buildInbuiltPrompt(role)
    }

    /** 内置默认 prompt（当 assets 文件不存在时使用） */
    private fun buildInbuiltPrompt(role: AgentRole): String = when (role) {
        AgentRole.ANALYST -> """
            你是投委会**分析师**（Bull Case，看多立场）。
            职责：构建多头论据、估值框架、回顾前次预测准确性。
            
            核心原则：
            - 始终寻找价值被低估的证据
            - 提供具体估值区间（PE/PB/DCF）
            - 引用数据时注明来源和时间
            - 如与对手达成共识，明确说出【CONSENSUS REACHED】
            
            会议目标：年化收益 ≥ 10%，最大回撤 ≤ 15%，基准：上证综指
        """.trimIndent()

        AgentRole.RISK_OFFICER -> """
            你是投委会**风险官**（Bear Case，看空立场）。
            职责：构建空头论据、识别风险日历、质疑多头假设。
            
            核心原则：
            - 寻找估值泡沫、基本面恶化的证据
            - 量化下行风险（最坏情形/压力测试）
            - 识别黑天鹅事件、政策风险
            - 如与对手达成共识，明确说出【CONSENSUS REACHED】
            
            风险约束：最大回撤 ≤ 15%
        """.trimIndent()

        AgentRole.STRATEGIST -> """
            你是投委会**策略师**（中立/框架立场）。
            职责：Top-down 宏观策略框架、入场评估、跨会议一致性检查。
            
            核心原则：
            - 从宏观（利率、汇率、政策）到微观（行业、公司）自上而下分析
            - 检查本次决策是否与前序决议冲突
            - 评估当前市场环境是否适合入场
            - 如各方达成共识，明确说出【CONSENSUS REACHED】
        """.trimIndent()

        AgentRole.EXECUTOR -> """
            你是投委会**执行员**（方案立场）。
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
            你是投委会**情报员**（事实立场）。
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
            你是投委会**监督员**（评判立场）。
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

    private fun buildUserMessage(ctx: MicContext): String {
        val phaseDesc = when (ctx.phase) {
            MeetingState.VALIDATING       -> "【入场评估】请评估本次会议的合规性与标的适格性"
            MeetingState.PREPPING         -> "【准备阶段】请完成你的准备工作"
            MeetingState.PHASE1_DEBATE    -> "【辩论 第${ctx.round}轮】请发表你的观点，你必须回应其他成员的论点"
            MeetingState.PHASE2_ASSESSMENT -> "【风险评估 第${ctx.round}轮】请基于已有讨论进行评估"
            MeetingState.FINAL_RATING     -> "【发布评级】请基于全部讨论发布最终投资评级与执行方案"
            MeetingState.PHASE1_ADJUDICATING -> "【仲裁】轮次已用尽，请基于全部发言作出最终裁决"
            MeetingState.COMPLETED        -> "【撰写纪要】请撰写本次会议完整纪要"
            else -> "【${ctx.phase.displayName}】请执行你的职责"
        }

        return buildString {
            appendLine("$phaseDesc")
            appendLine()
            appendLine("标的：${ctx.subject}")
            appendLine("会议ID：${ctx.traceId}")
            if (ctx.task.isNotBlank()) {
                appendLine()
                appendLine("任务说明：${ctx.task}")
            }
            // 注入全部历史发言，让每个 agent 能看到之前所有人的观点
            val prevSpeeches = ctx.previousSpeeches.filter { !it.isStreaming }
            if (prevSpeeches.isNotEmpty()) {
                appendLine()
                appendLine("━━━ 会议记录 ━━━")
                for ((index, speech) in prevSpeeches.withIndex()) {
                    appendLine()
                    appendLine("【${speech.agent.displayName}】(第${speech.round}轮):")
                    appendLine(speech.content)
                }
                appendLine()
                appendLine("━━━ 以上为历史记录，请基于以上讨论发表你的观点 ━━━")
            }
        }
    }

    private fun extractSummary(text: String): String =
        text.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: text.take(100)

    private fun extractRating(text: String): String? {
        val pattern = Regex("【最终评级】(Buy|Overweight|Hold\\+|Hold|Underweight|Sell)")
        return pattern.find(text)?.groupValues?.get(1)
    }
}

data class AgentResponse(
    val role: AgentRole,
    val content: String,
    val summary: String,
    val hasConsensus: Boolean,
    val rating: String? = null,
)

interface ApiKeyProvider {
    fun getKey(): String
}