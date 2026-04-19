package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.engine.LlmConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DynamicToolRegistry — 动态 Tool 注册与执行引擎
 *
 * 参考 Hermes Agent 的 registry.py 架构：
 *   - built-in 工具：web_search / web_extract，始终注册（不依赖 DB）
 *   - 用户自定义工具：从 DB skill_definitions 加载
 *   - 统一 OpenAI function calling schema
 *   - 统一 dispatch 执行
 *
 * 支持的 executionType（DB 工具）:
 *   - "http" → 模板化 HTTP 请求（URL/header/body 里的 {{param}} 替换）
 *   - "llm"  → 用 LLM 处理（systemPromptTemplate 里的 {{param}} 替换）
 */
class DynamicToolRegistry(
    private val skillDao: com.znliang.committee.data.db.SkillDefinitionDao,
    private val gson: Gson,
    private val okHttp: OkHttpClient,
    private val configProvider: suspend () -> com.znliang.committee.engine.LlmConfig,
    private val webSearchService: WebSearchService,
) {
    companion object {
        private const val TAG = "DynamicToolRegistry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // ── Built-in 工具 Schema（参考 Hermes web_tools.py WEB_SEARCH_SCHEMA） ──
        private val WEB_SEARCH_SCHEMA = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "web_search",
                "description" to "搜索互联网获取最新信息。返回搜索结果列表（标题、URL、摘要）。适用于查询财务数据、新闻、市场行情等。当你的内部知识不足以回答时使用。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "搜索关键词"
                        ),
                    ),
                    "required" to listOf("query"),
                ),
            )
        )

        private val WEB_EXTRACT_SCHEMA = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "web_extract",
                "description" to "提取网页的全文内容。输入 URL 列表，返回每个页面的文字内容。适用于深入阅读搜索结果中的具体页面。每次最多提取 3 个 URL。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "urls" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "要提取内容的 URL 列表（最多 3 个）",
                        ),
                    ),
                    "required" to listOf("urls"),
                ),
            )
        )

        private val BUILTIN_SCHEMAS = listOf(WEB_SEARCH_SCHEMA, WEB_EXTRACT_SCHEMA)
    }

    @Volatile
    private var cachedSkills: List<SkillDefinitionEntity> = emptyList()

    /** 刷新缓存（每次开会前调用） */
    suspend fun refresh() {
        cachedSkills = try {
            skillDao.getAllEnabled().first()
        } catch (e: Exception) {
            Log.w(TAG, "[refresh] failed: ${e.message}")
            emptyList()
        }
        Log.d(TAG, "[refresh] loaded ${cachedSkills.size} skills")
    }

    fun getLoadedSkills(): List<SkillDefinitionEntity> = cachedSkills

    /** 生成 OpenAI function calling tools schema（built-in + DB 自定义） */
    fun buildToolsSchema(): List<Map<String, Any>> {
        val dbSchemas = cachedSkills.map { skill ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to skill.name,
                    "description" to skill.description,
                    "parameters" to parseJson(skill.parameters),
                )
            )
        }
        // built-in 工具始终在最前面，参考 Hermes registry.get_schemas()
        return BUILTIN_SCHEMAS + dbSchemas
    }

    /** built-in 工具名集合 */
    private val builtinNames = setOf("web_search", "web_extract")

    /** 判断是否为 built-in 工具 */
    fun isBuiltinTool(name: String) = name in builtinNames

    /** 暴露给 AgentRuntime 的 built-in 工具执行入口（用于预搜索等场景） */
    suspend fun executeBuiltinTool(toolName: String, arguments: String): String {
        return if (toolName in builtinNames) executeBuiltin(toolName, arguments)
        else "[error] not a builtin tool: $toolName"
    }

    /** 执行 LLM 返回的 tool_call（built-in 优先，其次 DB 自定义） */
    suspend fun executeToolCall(toolName: String, arguments: String): String {
        // ── built-in 工具分发 ──
        if (toolName in builtinNames) {
            return executeBuiltin(toolName, arguments)
        }

        // ── DB 自定义工具分发 ──
        val skill = cachedSkills.find { it.name == toolName }
        if (skill == null) {
            Log.w(TAG, "[exec] unknown tool: $toolName")
            return "[error] tool not found: $toolName"
        }

        val args: Map<String, Any> = try {
            parseJson(arguments)
        } catch (e: Exception) {
            return "[error] arg parse failed: ${e.message}"
        }

        Log.d(TAG, "[exec] call $toolName args=$args")

        return try {
            when (skill.executionType) {
                "http" -> executeHttp(skill, args)
                "llm"  -> executeLlm(skill, args)
                else   -> "[error] unsupported type: ${skill.executionType}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "[exec] $toolName failed: ${e.message}")
            "[tool $toolName failed: ${e.message}]"
        }
    }

    // ── Built-in 工具执行（参考 Hermes web_tools.py handler） ──

    private suspend fun executeBuiltin(toolName: String, arguments: String): String {
        val args: Map<String, Any> = try {
            parseJson(arguments)
        } catch (e: Exception) {
            return "[error] arg parse failed: ${e.message}"
        }

        return try {
            when (toolName) {
                "web_search" -> {
                    val query = args["query"] as? String ?: return "[error] missing query"
                    val today = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US))
                    Log.d(TAG, "[web_search] query=$query date=$today")
                    val results = webSearchService.search(query)
                    if (results.isEmpty()) "[无搜索结果]" else {
                        // 搜索结果头部标注日期，让 LLM 知道信息的新鲜度
                        val header = "[搜索日期: $today]"
                        "$header\n${gson.toJson(results)}"
                    }
                }
                "web_extract" -> {
                    @Suppress("UNCHECKED_CAST")
                    val urls = (args["urls"] as? List<String>) ?: return "[error] missing urls"
                    val limited = urls.take(3)
                    Log.d(TAG, "[web_extract] urls=$limited")
                    val extractResults = webSearchService.extract(limited)
                    val contents = extractResults.map { result ->
                        mapOf("url" to result.url, "content" to result.content)
                    }
                    gson.toJson(contents)
                }
                else -> "[error] unknown builtin: $toolName"
            }
        } catch (e: Exception) {
            Log.w(TAG, "[builtin] $toolName failed: ${e.message}")
            "[tool $toolName failed: ${e.message}]"
        }
    }

    // ── HTTP 执行 ────────────────────────────────────────────────

    private fun executeHttp(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val config: Map<String, Any> = parseJson(skill.executionConfig)
        val urlTemplate = config["url"] as? String ?: return "[error] missing url"
        val method = ((config["method"] as? String) ?: "GET").uppercase()
        @Suppress("UNCHECKED_CAST")
        val headersRaw = config["headers"] as? Map<String, String> ?: emptyMap()
        val bodyTemplate = config["bodyTemplate"] as? String

        val url = replaceTemplate(urlTemplate, args)
        val headers = headersRaw.mapValues { replaceTemplate(it.value, args) }

        val request = Request.Builder().url(url)
        headers.forEach { (k, v) -> request.addHeader(k, v) }

        if (method in listOf("POST", "PUT", "PATCH") && bodyTemplate != null) {
            val body = replaceTemplate(bodyTemplate, args)
            request.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            request.method(method, null)
        }

        val client = okHttp.newBuilder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request.build()).execute()
        val responseBody = response.body?.string()?.take(4000) ?: ""

        if (!response.isSuccessful) {
            return "[HTTP ${response.code}] ${responseBody.take(200)}"
        }

        Log.d(TAG, "[HTTP] ${skill.name} ok, ${responseBody.length} chars")
        return responseBody
    }

    // ── LLM 执行 ────────────────────────────────────────────────

    private suspend fun executeLlm(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val config = configProvider()
        if (!config.isReady) return "[error] LLM API key not set"

        val configMap: Map<String, Any> = parseJson(skill.executionConfig)
        val promptTemplate = configMap["systemPromptTemplate"] as? String
            ?: return "[error] missing systemPromptTemplate"

        val systemPrompt = replaceTemplate(promptTemplate, args)
        val userPrompt = args.entries.joinToString("\n") { "${it.key}: ${it.value}" }

        val url = "${config.baseUrl}${config.provider.chatEndpoint}"
        val body = gson.toJson(mapOf(
            "model" to config.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
            "max_tokens" to 1024,
            "stream" to false,
        ))

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val client = okHttp.newBuilder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        val responseText = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return "[LLM call failed HTTP ${response.code}]"
        }

        val content: String = try {
            val json = gson.fromJson(responseText, Map::class.java) as? Map<*, *>
            val choices = json?.get("choices") as? List<Map<String, Any>>
            val msg = choices?.firstOrNull()?.get("message") as? Map<String, Any>
            msg?.get("content") as? String ?: ""
        } catch (_: Exception) { "" }

        Log.d(TAG, "[LLM] ${skill.name} ok, ${content.length} chars")
        return content
    }

    // ── 工具方法 ─────────────────────────────────────────────────

    private fun replaceTemplate(template: String, args: Map<String, Any>): String {
        var result = template
        args.forEach { (key, value) ->
            result = result.replace("{{$key}}", value.toString())
        }
        return result
    }

    private fun parseJson(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return gson.fromJson<Map<String, Any>>(json, type) ?: emptyMap()
    }
}
