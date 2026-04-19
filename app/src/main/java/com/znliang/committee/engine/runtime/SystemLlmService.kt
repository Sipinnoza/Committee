package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.engine.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  SystemLlmService — 系统 LLM 调用（无 Agent 身份）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  用于 Supervisor 结束判断、点评、评级、摘要、反思等系统级调用。
 *  不携带 Agent persona，不写入 Agent 聊天记录。
 *
 *  调用链：SystemLlmService → AgentPool.callSystemStreaming() → OkHttp SSE
 */
class SystemLlmService(
    private val callStreaming: suspend (LlmConfig, String, String) -> Flow<String>,
    private val configProvider: suspend () -> LlmConfig,
) {
    companion object {
        private const val TAG = "SystemLlm"
    }

    /**
     * 纯系统 LLM 调用。传入 system prompt + user prompt，返回完整响应文本。
     */
    suspend fun call(systemPrompt: String, userPrompt: String = ""): String {
        val config = configProvider()
        if (!config.isReady) {
            Log.w(TAG, "[call] API key 未配置")
            return ""
        }
        val sb = StringBuilder()
        try {
            callStreaming(config, systemPrompt, userPrompt)
                .collect { sb.append(it) }
        } catch (e: Exception) {
            Log.w(TAG, "[call] 错误: ${e.javaClass.simpleName}: ${e.message}")
        }
        return sb.toString()
    }

    /**
     * 简化版：只有 system prompt，user 为空。
     */
    suspend fun quickCall(systemPrompt: String): String {
        return call(systemPrompt, "请执行")
    }
}
