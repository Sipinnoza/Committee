package com.znliang.committee.engine.runtime

/**
 * ToolExecutor — 工具执行抽象层
 *
 * DynamicToolRegistry 实现此接口；AgentRuntime 通过此接口解耦具体工具实现。
 * 测试时可注入 FakeToolExecutor。
 */
interface ToolExecutor {
    suspend fun refresh()
    fun buildToolsSchema(): List<Map<String, Any>>
    suspend fun executeToolCall(name: String, arguments: String): String
    suspend fun executeBuiltinTool(toolName: String, arguments: String): String
    fun isBuiltinTool(name: String): Boolean
}
