package com.znliang.committee.engine.runtime.tools

import android.util.Log
import com.google.gson.Gson
import com.znliang.committee.engine.runtime.WebSearchService

/**
 * Built-in tool executor — web_search / web_extract。
 * 始终注册，不依赖 DB skill 定义。
 */
class BuiltinToolExecutor(
    private val webSearchService: WebSearchService,
    private val gson: Gson,
) {
    companion object {
        private const val TAG = "BuiltinTools"

        val WEB_SEARCH_SCHEMA = mapOf(
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

        val WEB_EXTRACT_SCHEMA = mapOf(
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

        val SCHEMAS = listOf(WEB_SEARCH_SCHEMA, WEB_EXTRACT_SCHEMA)
        val BUILTIN_NAMES = setOf("web_search", "web_extract")
    }

    fun isBuiltin(name: String) = name in BUILTIN_NAMES

    suspend fun execute(toolName: String, args: Map<String, Any>): String {
        return try {
            when (toolName) {
                "web_search" -> {
                    val query = args["query"] as? String ?: return "[error] missing query"
                    val today = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US))
                    Log.d(TAG, "[web_search] query=$query date=$today")
                    val results = webSearchService.search(query)
                    if (results.isEmpty()) "[无搜索结果]" else {
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
}
