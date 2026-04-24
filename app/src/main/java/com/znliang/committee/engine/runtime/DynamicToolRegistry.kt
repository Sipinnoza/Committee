package com.znliang.committee.engine.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.znliang.committee.data.db.CommitteeDatabase
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.engine.LlmConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.ContextFactory

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
 *   - "http"       → 模板化 HTTP 请求（URL/header/body 里的 {{param}} 替换）
 *   - "llm"        → 用 LLM 处理（systemPromptTemplate 里的 {{param}} 替换）
 *   - "javascript"  → 本地 Rhino JS 沙箱执行
 *   - "intent"      → Android Intent 交互（fire-and-forget）
 *   - "db_query"    → 本地 Room DB 只读查询
 *   - "chain"       → 顺序工具链编排（递归调用 executeToolCall）
 *   - "regex"       → 本地正则提取 / 替换
 */
class DynamicToolRegistry(
    private val skillDao: com.znliang.committee.data.db.SkillDefinitionDao,
    private val gson: Gson,
    private val okHttp: OkHttpClient,
    private val configProvider: suspend () -> com.znliang.committee.engine.LlmConfig,
    private val webSearchService: WebSearchService,
    private val appContext: Context,
    private val database: CommitteeDatabase,
) {
    companion object {
        private const val TAG = "DynamicToolRegistry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_CHAIN_STEPS = 5

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
            when (skill.executionType.lowercase()) {
                "http"       -> executeHttp(skill, args)
                "llm"        -> executeLlm(skill, args)
                "javascript" -> executeJavascript(skill, args)
                "intent"     -> executeIntent(skill, args)
                "db_query"   -> executeDbQuery(skill, args)
                "chain"      -> executeChain(skill, args)
                "regex"      -> executeRegex(skill, args)
                else         -> "[error] unsupported type: ${skill.executionType}"
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

    // ── HTTP 执行（带 SSRF 防护）─────────────────────────────────

    /** 禁止请求的内网地址模式 */
    private fun isInternalUrl(url: String): Boolean {
        return try {
            val host = java.net.URL(url).host.lowercase()
            host == "localhost" ||
                host == "127.0.0.1" ||
                host == "0.0.0.0" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("172.") && run {
                    val second = host.removePrefix("172.").substringBefore(".").toIntOrNull() ?: 0
                    second in 16..31
                } ||
                host.endsWith(".local") ||
                host.endsWith(".internal") ||
                host == "metadata.google.internal" ||
                host == "169.254.169.254" // AWS/GCP metadata
        } catch (_: Exception) { true } // 解析失败则拒绝
    }

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

        // ── SSRF 防护：禁止内网请求 ──
        if (isInternalUrl(url)) {
            Log.w(TAG, "[HTTP] SSRF blocked: $url")
            return "[error] request to internal network is not allowed"
        }

        // ── 协议限制：仅 HTTP/HTTPS ──
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "[error] only http/https protocols are allowed"
        }

        val headers = headersRaw.mapValues { replaceTemplate(it.value, args) }

        val request = Request.Builder().url(url)
        headers.forEach { (k, v) -> request.addHeader(k, v) }

        if (method in listOf("POST", "PUT", "PATCH") && bodyTemplate != null) {
            val body = replaceTemplate(bodyTemplate, args)
            request.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            request.method(method, null)
        }

        val response = okHttp.newCall(request.build()).execute()
        return response.use { resp ->
            val responseBody = resp.body?.string()?.take(4000) ?: ""
            if (!resp.isSuccessful) {
                "[HTTP ${resp.code}] ${responseBody.take(200)}"
            } else {
                Log.d(TAG, "[HTTP] ${skill.name} ok, ${responseBody.length} chars")
                responseBody
            }
        }
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

        val client = okHttp
        val response = client.newCall(request).execute()
        return response.use { resp ->
            val responseText = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@use "[LLM call failed HTTP ${resp.code}]"
            }

            val content: String = try {
                val json = gson.fromJson(responseText, Map::class.java) as? Map<*, *>
                val choices = json?.get("choices") as? List<Map<String, Any>>
                val msg = choices?.firstOrNull()?.get("message") as? Map<String, Any>
                msg?.get("content") as? String ?: ""
            } catch (_: Exception) { "" }

            Log.d(TAG, "[LLM] ${skill.name} ok, ${content.length} chars")
            content
        }
    }

    // ── JavaScript 沙箱执行（Rhino + ClassShutter 安全限制） ──────

    /**
     * 安全沙箱：通过 ClassShutter 白名单限制 JS 只能访问基础类型。
     * 禁止访问 java.lang.Runtime、ProcessBuilder、File、网络等危险类。
     */
    private val safeClassShutter = object : org.mozilla.javascript.ClassShutter {
        private val ALLOWED_PREFIXES = setOf(
            "java.lang.String",
            "java.lang.Number",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Math",
            "java.util.Arrays",
        )

        override fun visibleToScripts(fullClassName: String): Boolean {
            return ALLOWED_PREFIXES.any { fullClassName == it || fullClassName.startsWith("$it\$") }
        }
    }

    private suspend fun executeJavascript(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val configMap: Map<String, Any> = parseJson(skill.executionConfig)
        val script = configMap["script"] as? String
            ?: return "[error] missing script in executionConfig"

        // ── 静态禁止危险代码模式 ──
        val dangerousPatterns = listOf(
            "java.lang.Runtime", "ProcessBuilder", "java.io.File",
            "java.net.", "javax.net.", "Packages.", "importClass", "importPackage",
            "getClass()", ".class.forName", "ClassLoader",
        )
        dangerousPatterns.forEach { pattern ->
            if (script.contains(pattern, ignoreCase = true)) {
                return "[error] forbidden code pattern: $pattern"
            }
        }

        return withTimeout(5_000L) {
            val factory = ContextFactory.getGlobal()
            factory.call { cx ->
                // 强制解释模式 — Android 不支持字节码生成
                cx.optimizationLevel = -1
                cx.instructionObserverThreshold = 50_000

                // ── ClassShutter: 限制 Java 类访问 ──
                cx.setClassShutter(safeClassShutter)

                val scope = cx.initStandardObjects(null, true) // sealed=true 防止修改内置对象

                // 注入 input（完整 args JSON）
                val inputJson = gson.toJson(args)
                org.mozilla.javascript.ScriptableObject.putProperty(scope, "input", inputJson)

                // 注入各参数为顶层变量
                args.forEach { (key, value) ->
                    org.mozilla.javascript.ScriptableObject.putProperty(scope, key, value.toString())
                }

                val result = cx.evaluateString(scope, script, skill.name, 1, null)
                val output = org.mozilla.javascript.Context.toString(result)

                Log.d(TAG, "[JS] ${skill.name} ok, ${output.length} chars")
                output
            } as String
        }
    }

    // ── Android Intent 交互（带安全限制）────────────────────────

    /** 允许的 Intent action 白名单 */
    private val ALLOWED_INTENT_ACTIONS = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
        Intent.ACTION_SEARCH,
        Intent.ACTION_WEB_SEARCH,
        "android.intent.action.OPEN_DOCUMENT",
    )

    private fun executeIntent(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val configMap: Map<String, Any> = parseJson(skill.executionConfig)

        val action = replaceTemplate(configMap["action"] as? String ?: "", args)
        val type = configMap["type"] as? String
        val uriStr = configMap["uri"] as? String
        val pkg = configMap["package"] as? String

        // ── 安全限制：Action 白名单 ──
        if (action !in ALLOWED_INTENT_ACTIONS) {
            Log.w(TAG, "[Intent] blocked action: $action")
            return "[error] intent action not allowed: $action"
        }

        @Suppress("UNCHECKED_CAST")
        val extras = configMap["extras"] as? Map<String, String> ?: emptyMap()

        // ── 安全限制：禁止 file:// URI 和内容 URI 滥用 ──
        val parsedUri = uriStr?.let { Uri.parse(replaceTemplate(it, args)) }
        if (parsedUri != null) {
            val scheme = parsedUri.scheme?.lowercase()
            if (scheme == "file" || scheme == "content") {
                Log.w(TAG, "[Intent] blocked URI scheme: $scheme")
                return "[error] file:// and content:// URIs are not allowed"
            }
        }

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (parsedUri != null && type != null) {
                setDataAndType(parsedUri, type)
            } else {
                parsedUri?.let { data = it }
                type?.let { setType(it) }
            }

            pkg?.let { setPackage(it) }

            extras.forEach { (key, value) ->
                putExtra(key, replaceTemplate(value, args))
            }
        }

        return try {
            appContext.startActivity(intent)
            Log.d(TAG, "[Intent] ${skill.name} fired: action=$action")
            "Intent fired: action=$action" + if (type != null) ", type=$type" else ""
        } catch (e: Exception) {
            "[error] Intent failed: ${e.message}"
        }
    }

    // ── DB 只读查询（参数化防注入）──────────────────────────────

    /**
     * 允许查询的表白名单 — 仅可查询 Room 管理的业务表。
     * 白名单方式是防止 SQL 注入的根本保障，即使参数化查询也需要限制表范围。
     */
    private val ALLOWED_TABLES = setOf(
        "events", "speeches", "meeting_sessions", "agent_chat_messages",
        "agent_evolution", "agent_skills", "prompt_changelog", "meeting_outcomes",
        "skill_definitions", "app_config", "meeting_materials", "decision_actions",
    )

    private fun executeDbQuery(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val configMap: Map<String, Any> = parseJson(skill.executionConfig)
        val queryTemplate = configMap["query"] as? String
            ?: return "[error] missing query in executionConfig"
        val maxRows = (configMap["max_rows"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 10

        // ── 构建参数化查询：将 {{param}} 替换为 ? 占位符 ──
        val paramNames = mutableListOf<String>()
        val parameterizedSql = queryTemplate.replace(Regex("\\{\\{(\\w+)\\}\\}")) { match ->
            paramNames.add(match.groupValues[1])
            "?"
        }.trim()

        // ── 安全检查：必须 SELECT 开头 + 禁止写操作关键字 ──
        val upper = parameterizedSql.uppercase()
        if (!upper.startsWith("SELECT")) {
            return "[error] only SELECT queries are allowed"
        }
        val forbidden = listOf("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH", "DETACH", "PRAGMA")
        forbidden.forEach { kw ->
            if (Regex("\\b$kw\\b").containsMatchIn(upper)) {
                return "[error] forbidden keyword: $kw"
            }
        }

        // ── 禁止多语句（防止 ; DROP TABLE 攻击）──
        if (parameterizedSql.contains(";")) {
            return "[error] multiple statements not allowed"
        }

        // ── 表白名单检查 ──
        val fromTablePattern = Regex("\\bFROM\\s+(\\w+)", RegexOption.IGNORE_CASE)
        val joinTablePattern = Regex("\\bJOIN\\s+(\\w+)", RegexOption.IGNORE_CASE)
        val referencedTables = (fromTablePattern.findAll(parameterizedSql) + joinTablePattern.findAll(parameterizedSql))
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val disallowed = referencedTables - ALLOWED_TABLES
        if (disallowed.isNotEmpty()) {
            return "[error] table not allowed: ${disallowed.joinToString()}"
        }

        // ── 绑定参数值 ──
        val bindArgs = paramNames.map { name ->
            args[name]?.toString() ?: ""
        }.toTypedArray()

        val db = database.openHelper.readableDatabase
        val cursor = db.query(parameterizedSql, bindArgs)

        return try {
            val rows = mutableListOf<Map<String, Any?>>()
            val colNames = cursor.columnNames
            var count = 0
            while (cursor.moveToNext() && count < maxRows) {
                val row = mutableMapOf<String, Any?>()
                for (i in colNames.indices) {
                    row[colNames[i]] = when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_BLOB -> "[blob ${cursor.getBlob(i).size} bytes]"
                        else -> cursor.getString(i)
                    }
                }
                rows.add(row)
                count++
            }
            Log.d(TAG, "[DB] ${skill.name} returned $count rows")
            gson.toJson(rows)
        } finally {
            cursor.close()
        }
    }

    // ── 顺序工具链编排 ──────────────────────────────────────────

    private suspend fun executeChain(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val configMap: Map<String, Any> = parseJson(skill.executionConfig)
        @Suppress("UNCHECKED_CAST")
        val steps = configMap["steps"] as? List<Map<String, Any>>
            ?: return "[error] missing steps in executionConfig"

        if (steps.size > MAX_CHAIN_STEPS) {
            return "[error] chain exceeds max $MAX_CHAIN_STEPS steps"
        }

        var prevResult = ""
        val results = mutableListOf<String>()

        for ((index, step) in steps.withIndex()) {
            val toolName = step["tool"] as? String
                ?: return "[error] step ${index + 1} missing 'tool'"

            @Suppress("UNCHECKED_CAST")
            val stepArgs = step["args"] as? Map<String, Any> ?: emptyMap()

            // 替换 {{param}} 用原始入参，$PREV 用上一步结果
            val resolvedArgs = stepArgs.mapValues { (_, value) ->
                var s = value.toString()
                args.forEach { (k, v) -> s = s.replace("{{$k}}", v.toString()) }
                s = s.replace("\$PREV", prevResult)
                s
            }

            val argsJson = gson.toJson(resolvedArgs)
            Log.d(TAG, "[Chain] step ${index + 1}/${ steps.size}: $toolName args=$argsJson")

            val result = executeToolCall(toolName, argsJson)
            results.add(result)

            if (result.startsWith("[error]")) {
                Log.w(TAG, "[Chain] step ${index + 1} failed, aborting chain")
                return results.joinToString("\n---\n")
            }

            prevResult = result
        }

        return results.joinToString("\n---\n")
    }

    // ── 正则提取 / 替换 ──────────────────────────────────────────

    private fun executeRegex(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        val configMap: Map<String, Any> = parseJson(skill.executionConfig)
        val pattern = configMap["pattern"] as? String
            ?: return "[error] missing pattern in executionConfig"
        val inputTemplate = configMap["input"] as? String ?: ""
        val group = (configMap["group"] as? Number)?.toInt() ?: 0
        val findAll = configMap["findAll"] == true
        val replacement = configMap["replacement"] as? String

        val input = replaceTemplate(inputTemplate, args)
        val regex = Regex(pattern)

        // 替换模式
        if (replacement != null) {
            val result = regex.replace(input, replacement)
            Log.d(TAG, "[Regex] ${skill.name} replace, ${result.length} chars")
            return result
        }

        // findAll 模式 → JSON 数组
        if (findAll) {
            val matches = regex.findAll(input).map { match ->
                if (group in 0..match.groupValues.lastIndex) match.groupValues[group]
                else match.value
            }.toList()
            Log.d(TAG, "[Regex] ${skill.name} findAll, ${matches.size} matches")
            return gson.toJson(matches)
        }

        // 默认 → 首个匹配
        val match = regex.find(input) ?: return ""
        val result = if (group in 0..match.groupValues.lastIndex) match.groupValues[group] else match.value
        Log.d(TAG, "[Regex] ${skill.name} first match: $result")
        return result
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
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson<Map<String, Any>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "[parseJson] failed: ${e.message}")
            emptyMap()
        }
    }
}
