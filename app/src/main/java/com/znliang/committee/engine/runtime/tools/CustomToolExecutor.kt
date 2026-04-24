package com.znliang.committee.engine.runtime.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.znliang.committee.data.db.CommitteeDatabase
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.engine.LlmConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.ContextFactory

/**
 * Custom tool executor — http/llm/js/intent/db_query/chain/regex（含安全防护）。
 * 从 DB skill_definitions 加载的用户自定义工具。
 */
class CustomToolExecutor(
    private val gson: Gson,
    private val okHttp: OkHttpClient,
    private val configProvider: suspend () -> LlmConfig,
    private val appContext: Context,
    private val database: CommitteeDatabase,
    private val executeToolCallFn: suspend (String, String) -> String,
) {
    companion object {
        private const val TAG = "CustomToolExec"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_CHAIN_STEPS = 5
    }

    suspend fun execute(
        skill: SkillDefinitionEntity,
        args: Map<String, Any>,
    ): String {
        return when (skill.executionType.lowercase()) {
            "http"       -> executeHttp(skill, args)
            "llm"        -> executeLlm(skill, args)
            "javascript" -> executeJavascript(skill, args)
            "intent"     -> executeIntent(skill, args)
            "db_query"   -> executeDbQuery(skill, args)
            "chain"      -> executeChain(skill, args)
            "regex"      -> executeRegex(skill, args)
            else         -> "[error] unsupported type: ${skill.executionType}"
        }
    }

    // ── HTTP 执行（带 SSRF 防护）─────────────────────────────────

    private fun isInternalUrl(url: String): Boolean {
        return try {
            val parsed = java.net.URL(url)
            val host = parsed.host.lowercase()
            // Hostname-based checks
            if (host == "localhost" || host == "0.0.0.0" ||
                host.endsWith(".local") || host.endsWith(".internal") ||
                host == "metadata.google.internal" || host == "169.254.169.254" ||
                host == "[::1]" || host == "[::ffff:127.0.0.1]"
            ) return true
            // Resolve hostname and check resolved IPs
            val addresses = java.net.InetAddress.getAllByName(host)
            addresses.any { addr ->
                addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isAnyLocalAddress
            }
        } catch (_: Exception) { true } // block on resolution failure
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

        if (isInternalUrl(url)) {
            Log.w(TAG, "[HTTP] SSRF blocked: $url")
            return "[error] request to internal network is not allowed"
        }
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

        val response = okHttp.newCall(request).execute()
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

    private val safeClassShutter = object : org.mozilla.javascript.ClassShutter {
        private val ALLOWED_PREFIXES = setOf(
            "java.lang.String", "java.lang.Number", "java.lang.Integer",
            "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Boolean", "java.lang.Math", "java.util.Arrays",
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
                cx.optimizationLevel = -1
                cx.instructionObserverThreshold = 50_000
                cx.setClassShutter(safeClassShutter)
                val scope = cx.initStandardObjects(null, true)
                val inputJson = gson.toJson(args)
                org.mozilla.javascript.ScriptableObject.putProperty(scope, "input", inputJson)
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

    private val ALLOWED_INTENT_ACTIONS = setOf(
        Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SENDTO,
        Intent.ACTION_SEARCH, Intent.ACTION_WEB_SEARCH,
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

        if (action !in ALLOWED_INTENT_ACTIONS) {
            Log.w(TAG, "[Intent] blocked action: $action")
            return "[error] intent action not allowed: $action"
        }

        @Suppress("UNCHECKED_CAST")
        val extras = configMap["extras"] as? Map<String, String> ?: emptyMap()

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
            if (parsedUri != null && type != null) setDataAndType(parsedUri, type)
            else {
                parsedUri?.let { data = it }
                type?.let { setType(it) }
            }
            pkg?.let { setPackage(it) }
            extras.forEach { (key, value) -> putExtra(key, replaceTemplate(value, args)) }
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

        val paramNames = mutableListOf<String>()
        val parameterizedSql = queryTemplate.replace(Regex("\\{\\{(\\w+)\\}\\}")) { match ->
            paramNames.add(match.groupValues[1])
            "?"
        }.trim()

        val upper = parameterizedSql.uppercase()
        if (!upper.startsWith("SELECT")) return "[error] only SELECT queries are allowed"
        val forbidden = listOf("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ATTACH", "DETACH", "PRAGMA")
        forbidden.forEach { kw ->
            if (Regex("\\b$kw\\b").containsMatchIn(upper)) return "[error] forbidden keyword: $kw"
        }
        if (parameterizedSql.contains(";")) return "[error] multiple statements not allowed"

        val fromTablePattern = Regex("\\bFROM\\s+(\\w+)", RegexOption.IGNORE_CASE)
        val joinTablePattern = Regex("\\bJOIN\\s+(\\w+)", RegexOption.IGNORE_CASE)
        val referencedTables = (fromTablePattern.findAll(parameterizedSql) + joinTablePattern.findAll(parameterizedSql))
            .map { it.groupValues[1].lowercase() }.toSet()
        val disallowed = referencedTables - ALLOWED_TABLES
        if (disallowed.isNotEmpty()) return "[error] table not allowed: ${disallowed.joinToString()}"

        val bindArgs = paramNames.map { name -> args[name]?.toString() ?: "" }.toTypedArray()
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

        if (steps.size > MAX_CHAIN_STEPS) return "[error] chain exceeds max $MAX_CHAIN_STEPS steps"

        var prevResult = ""
        val results = mutableListOf<String>()

        for ((index, step) in steps.withIndex()) {
            val toolName = step["tool"] as? String
                ?: return "[error] step ${index + 1} missing 'tool'"
            @Suppress("UNCHECKED_CAST")
            val stepArgs = step["args"] as? Map<String, Any> ?: emptyMap()
            val resolvedArgs = stepArgs.mapValues { (_, value) ->
                var s = value.toString()
                args.forEach { (k, v) -> s = s.replace("{{$k}}", v.toString()) }
                s = s.replace("\$PREV", prevResult)
                s
            }
            val argsJson = gson.toJson(resolvedArgs)
            Log.d(TAG, "[Chain] step ${index + 1}/${steps.size}: $toolName args=$argsJson")
            val result = executeToolCallFn(toolName, argsJson)
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

        if (replacement != null) {
            val result = regex.replace(input, replacement)
            Log.d(TAG, "[Regex] ${skill.name} replace, ${result.length} chars")
            return result
        }
        if (findAll) {
            val matches = regex.findAll(input).map { match ->
                if (group in 0..match.groupValues.lastIndex) match.groupValues[group] else match.value
            }.toList()
            Log.d(TAG, "[Regex] ${skill.name} findAll, ${matches.size} matches")
            return gson.toJson(matches)
        }
        val match = regex.find(input) ?: return ""
        val result = if (group in 0..match.groupValues.lastIndex) match.groupValues[group] else match.value
        Log.d(TAG, "[Regex] ${skill.name} first match: $result")
        return result
    }

    // ── 工具方法 ─────────────────────────────────────────────────

    private fun replaceTemplate(template: String, args: Map<String, Any>): String {
        var result = template
        args.forEach { (key, value) -> result = result.replace("{{$key}}", value.toString()) }
        return result
    }

    fun parseJson(json: String): Map<String, Any> {
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
