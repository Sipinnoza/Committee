package com.znliang.committee.engine

import com.znliang.committee.domain.model.MicContext
import kotlinx.coroutines.flow.Flow

/**
 * LlmClient — LLM 调用抽象层
 *
 * AgentPool 实现此接口；AgentRuntime 通过此接口解耦具体 LLM 实现。
 * 测试时可注入 FakeLlmClient。
 */
interface LlmClient {
    fun callAgentStreamingByRoleId(
        roleId: String,
        context: MicContext,
        systemPromptOverride: String? = null,
        materials: List<MaterialData> = emptyList(),
    ): Flow<StreamResult>

    fun callSystemStreaming(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
    ): Flow<StreamResult>

    fun getSystemPromptTextByRoleId(roleId: String): String?
}
