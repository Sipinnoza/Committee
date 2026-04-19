package com.znliang.committee.engine.runtime

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * WebSearchService — 参考 Hermes Agent 的 web_tools.py 双阶段搜索架构
 *
 * Stage 1: web_search — 关键词搜索，返回 URL + 摘要列表
 * Stage 2: web_extract — 提取具体页面全文内容
 *
 * 后端优先级：
 *   1. Tavily API（需要 API Key，质量最高）
 *   2. DuckDuckGo HTML（免费备用，无需 Key）
 */
class WebSearchService(
    private val okHttp: OkHttpClient,
    private val gson: Gson,
    private val tavilyKeyProvider: () -> String,
) {
    companion object {
        private const val TAG = "WebSearchService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TAVILY_SEARCH_URL = "https://api.tavily.com/search"
        private const val TAVILY_EXTRACT_URL = "https://api.tavily.com/extract"
        private const val MAX_SEARCH_RESULTS = 5
        private const val MAX_EXTRACT_CHARS = 4000
    }

    // ── 搜索结果 ─────────────────────────────────────────────────

    data class SearchResult(
        val title: String,
        val url: String,
        val content: String,
        val score: Double = 0.0,
    )

    data class ExtractResult(
        val url: String,
        val content: String,
    )

    // ── Stage 1: Search ──────────────────────────────────────────

    suspend fun search(query: String, maxResults: Int = MAX_SEARCH_RESULTS): List<SearchResult> {
        val tavilyKey = tavilyKeyProvider()
        return if (tavilyKey.isNotBlank()) {
            tavilySearch(query, maxResults, tavilyKey)
        } else {
            duckDuckGoSearch(query, maxResults)
        }
    }

    /**
     * 格式化搜索结果为 LLM 可读的文本块
     */
    fun formatSearchResults(results: List<SearchResult>): String {
        if (results.isEmpty()) return "[搜索无结果]"
        return results.mapIndexed { i, r ->
            "${i + 1}. **${r.title}**\n   URL: ${r.url}\n   ${r.content.take(300)}"
        }.joinToString("\n\n")
    }

    // ── Stage 2: Extract ─────────────────────────────────────────

    suspend fun extract(urls: List<String>): List<ExtractResult> {
        if (urls.isEmpty()) return emptyList()
        val tavilyKey = tavilyKeyProvider()
        return if (tavilyKey.isNotBlank()) {
            tavilyExtract(urls, tavilyKey)
        } else {
            jsoupExtract(urls)
        }
    }

    // ── Tavily 后端 ──────────────────────────────────────────────

    private suspend fun tavilySearch(
        query: String,
        maxResults: Int,
        apiKey: String,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "api_key" to apiKey,
                "query" to query,
                "max_results" to maxResults,
                "include_answer" to false,
                "include_raw_content" to false,
            ))
            val request = Request.Builder()
                .url(TAVILY_SEARCH_URL)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val client = okHttp.newBuilder()
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.w(TAG, "[Tavily] search failed: HTTP ${response.code}")
                return@withContext duckDuckGoSearch(query, maxResults)
            }

            val json = gson.fromJson<Map<String, Any>>(responseText, object : TypeToken<Map<String, Any>>() {}.type)
            @Suppress("UNCHECKED_CAST")
            val results = json["results"] as? List<Map<String, Any>> ?: emptyList()

            results.map { r ->
                SearchResult(
                    title = r["title"] as? String ?: "",
                    url = r["url"] as? String ?: "",
                    content = r["content"] as? String ?: "",
                    score = (r["score"] as? Number)?.toDouble() ?: 0.0,
                )
            }.also {
                Log.d(TAG, "[Tavily] search ok: ${it.size} results for '$query'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Tavily] search error: ${e.message}")
            duckDuckGoSearch(query, maxResults)
        }
    }

    private suspend fun tavilyExtract(
        urls: List<String>,
        apiKey: String,
    ): List<ExtractResult> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "api_key" to apiKey,
                "urls" to urls.take(5),
            ))
            val request = Request.Builder()
                .url(TAVILY_EXTRACT_URL)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val client = okHttp.newBuilder()
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.w(TAG, "[Tavily] extract failed: HTTP ${response.code}")
                return@withContext jsoupExtract(urls)
            }

            val json = gson.fromJson<Map<String, Any>>(responseText, object : TypeToken<Map<String, Any>>() {}.type)
            @Suppress("UNCHECKED_CAST")
            val results = json["results"] as? List<Map<String, Any>> ?: emptyList()

            results.mapNotNull { r ->
                val url = r["url"] as? String ?: return@mapNotNull null
                val content = r["raw_content"] as? String ?: ""
                if (content.isBlank()) return@mapNotNull null
                ExtractResult(url = url, content = content.take(MAX_EXTRACT_CHARS))
            }.also {
                Log.d(TAG, "[Tavily] extract ok: ${it.size} pages")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Tavily] extract error: ${e.message}")
            jsoupExtract(urls)
        }
    }

    // ── DuckDuckGo 免费备用 ──────────────────────────────────────

    private suspend fun duckDuckGoSearch(
        query: String,
        maxResults: Int,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            val client = okHttp.newBuilder()
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (!response.isSuccessful || html.isBlank()) {
                Log.w(TAG, "[DDG] search failed: HTTP ${response.code}")
                return@withContext emptyList()
            }

            // 解析 DDG HTML 结果页
            val doc = Jsoup.parse(html)
            val results = doc.select(".result").mapNotNull { el ->
                val titleEl = el.selectFirst(".result__title a") ?: return@mapNotNull null
                val snippetEl = el.selectFirst(".result__snippet")
                val href = titleEl.attr("href")
                // DDG 的 href 是 redirect URL，提取实际 URL
                val actualUrl = extractDdgUrl(href)
                SearchResult(
                    title = titleEl.text().trim(),
                    url = actualUrl,
                    content = snippetEl?.text()?.trim() ?: "",
                )
            }.take(maxResults)

            Log.i(TAG, "[DDG] search ok: ${results.size} results for '$query'")
            results
        } catch (e: Exception) {
            Log.w(TAG, "[DDG] search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 从 DDG redirect URL 中提取实际 URL
     * DDG href 格式: //duckduckgo.com/l/?uddg=<encoded_url>&...
     */
    private fun extractDdgUrl(href: String): String {
        if (!href.contains("uddg=")) return href
        val uddg = href.substringAfter("uddg=").substringBefore("&")
        return java.net.URLDecoder.decode(uddg, "UTF-8")
    }

    // ── JSoup 提取备用 ──────────────────────────────────────────

    private suspend fun jsoupExtract(urls: List<String>): List<ExtractResult> = withContext(Dispatchers.IO) {
        urls.mapNotNull { url ->
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .timeout(15000)
                    .get()
                // 去掉脚本/样式，提取纯文本
                doc.select("script, style, nav, footer, header").remove()
                val text = doc.body()?.text()?.take(MAX_EXTRACT_CHARS) ?: ""
                if (text.isBlank()) return@mapNotNull null
                ExtractResult(url = url, content = text)
            } catch (e: Exception) {
                Log.w(TAG, "[JSoup] extract failed for $url: ${e.message}")
                null
            }
        }
    }

    // ── 一站式搜索 + 提取（给情报官自动搜索用） ────────────────────

    /**
     * 搜索关键词，然后提取前 N 个结果的页面内容。
     * 返回格式化的完整文本，可直接注入 prompt。
     */
    suspend fun searchAndExtract(query: String, topN: Int = 3): String {
        val results = search(query, maxResults = topN)
        if (results.isEmpty()) return "[搜索 '$query' 无结果]"

        val urls = results.map { it.url }.filter { it.isNotBlank() }
        val extracts = if (urls.isNotEmpty()) extract(urls) else emptyList()
        val extractMap = extracts.associateBy { it.url }

        val sb = StringBuilder()
        sb.appendLine("## 搜索结果: $query")
        sb.appendLine()

        for (r in results) {
            sb.appendLine("### ${r.title}")
            sb.appendLine("URL: ${r.url}")
            sb.appendLine(r.content.take(200))
            val extracted = extractMap[r.url]
            if (extracted != null && extracted.content.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("页面内容摘要:")
                sb.appendLine(extracted.content.take(2000))
            }
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    // ── 检查 Tavily 是否可用 ──────────────────────────────────────

    fun hasTavilyKey(): Boolean = tavilyKeyProvider().isNotBlank()
}
