package com.znliang.committee.engine.runtime

import android.content.Context
import android.util.Log
import com.znliang.committee.data.db.CommitteeDatabase
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.runtime.tools.BuiltinToolExecutor
import com.znliang.committee.engine.runtime.tools.CustomToolExecutor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * DynamicToolRegistry — 动态 Tool 注册与执行引擎（瘦身版）
 *
 * Schema 构建 + 路由分发。具体执行委托给：
 * - BuiltinToolExecutor: web_search / web_extract
 * - CustomToolExecutor: http/llm/js/intent/db_query/chain/regex
 */
class DynamicToolRegistry(
    private val skillDao: com.znliang.committee.data.db.SkillDefinitionDao,
    private val gson: Gson,
    okHttp: OkHttpClient,
    configProvider: suspend () -> LlmConfig,
    webSearchService: WebSearchService,
    appContext: Context,
    database: CommitteeDatabase,
) : ToolExecutor {
    companion object {
        private const val TAG = "DynamicToolRegistry"
    }

    private val builtinExecutor = BuiltinToolExecutor(webSearchService, gson)

    private val customExecutor = CustomToolExecutor(
        gson = gson,
        okHttp = okHttp,
        configProvider = configProvider,
        appContext = appContext,
        database = database,
        executeToolCallFn = { name, args -> executeToolCall(name, args) },
    )

    @Volatile
    private var cachedSkills: List<SkillDefinitionEntity> = emptyList()

    /** 刷新缓存（每次开会前调用） */
    override suspend fun refresh() {
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
    override fun buildToolsSchema(): List<Map<String, Any>> {
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
        return BuiltinToolExecutor.SCHEMAS + dbSchemas
    }

    /** 判断是否为 built-in 工具 */
    override fun isBuiltinTool(name: String) = builtinExecutor.isBuiltin(name)

    /** 暴露给 AgentRuntime 的 built-in 工具执行入口（用于预搜索等场景） */
    override suspend fun executeBuiltinTool(toolName: String, arguments: String): String {
        if (!builtinExecutor.isBuiltin(toolName)) return "[error] not a builtin tool: $toolName"
        val args: Map<String, Any> = try {
            parseJson(arguments)
        } catch (e: Exception) {
            return "[error] arg parse failed: ${e.message}"
        }
        return builtinExecutor.execute(toolName, args)
    }

    /** 执行 LLM 返回的 tool_call（built-in 优先，其次 DB 自定义） */
    override suspend fun executeToolCall(toolName: String, arguments: String): String {
        val args: Map<String, Any> = try {
            parseJson(arguments)
        } catch (e: Exception) {
            return "[error] arg parse failed: ${e.message}"
        }

        // built-in 工具
        if (builtinExecutor.isBuiltin(toolName)) {
            return builtinExecutor.execute(toolName, args)
        }

        // DB 自定义工具
        val skill = cachedSkills.find { it.name == toolName }
        if (skill == null) {
            Log.w(TAG, "[exec] unknown tool: $toolName")
            return "[error] tool not found: $toolName"
        }

        Log.d(TAG, "[exec] call $toolName args=$args")

        return try {
            customExecutor.execute(skill, args)
        } catch (e: Exception) {
            Log.w(TAG, "[exec] $toolName failed: ${e.message}")
            "[tool $toolName failed: ${e.message}]"
        }
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
