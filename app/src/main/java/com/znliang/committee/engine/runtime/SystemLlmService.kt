package com.znliang.committee.engine.runtime

import android.util.Log
import com.znliang.committee.engine.LlmConfig
import com.znliang.committee.engine.StreamResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统 LLM 调用（无 Agent 身份）
 *
 * 用于 Supervisor 结束判断、点评、评级、摘要、反思等系统级调用。
 * 不携带 Agent persona，不写入 Agent 聊天记录。
 *
 * 调用链：SystemLlmService → AgentPool.callSystemStreaming() → OkHttp SSE
 */
class SystemLlmService(
    private val callStreaming: suspend (LlmConfig, String, String) -> Flow<StreamResult>,
    private val configProvider: suspend () -> LlmConfig,
) {
    companion object {
        private const val TAG = "SystemLlm"
        private const val CALL_TIMEOUT_MS = 60_000L  // 单次调用超时 60s
        private const val MAX_RETRIES = 1             // 失败后重试 1 次
    }

    /**
     * 纯系统 LLM 调用。传入 system prompt + user prompt，返回完整响应文本。
     * 自动过滤 StreamResult.Error 和 Done，只累积 Token。
     * 含超时保护（60s）和 1 次自动重试。
     */
    suspend fun call(systemPrompt: String, userPrompt: String = ""): String {
        val config = configProvider()
        if (!config.isReady) {
            Log.w(TAG, "[call] API key 未配置")
            return ""
        }
        for (attempt in 0..MAX_RETRIES) {
            val result = callOnce(config, systemPrompt, userPrompt)
            if (result.isNotBlank()) return result
            if (attempt < MAX_RETRIES) {
                Log.w(TAG, "[call] 第 ${attempt + 1} 次调用返回空，重试...")
                kotlinx.coroutines.delay(1000L) // 重试前等待 1s
            }
        }
        Log.w(TAG, "[call] 重试耗尽，返回空")
        return ""
    }

    private suspend fun callOnce(config: LlmConfig, systemPrompt: String, userPrompt: String): String {
        val sb = StringBuilder()
        try {
            val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                callStreaming(config, systemPrompt, userPrompt)
                    .collect { result ->
                        when (result) {
                            is StreamResult.Token -> sb.append(result.text)
                            is StreamResult.Error -> Log.w(TAG, "[call] 流式错误: ${result.type} ${result.message}")
                            is StreamResult.Done -> { /* no-op */ }
                        }
                    }
                sb.toString()
            }
            if (result == null) {
                Log.w(TAG, "[call] 调用超时 (${CALL_TIMEOUT_MS}ms)")
                return sb.toString() // 返回已收集的部分内容
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 不吞掉取消异常
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
