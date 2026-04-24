package com.znliang.committee.engine

/**
 * 类型化的流式结果 — 替代魔法字符串 "【计费错误】" / "【请求失败】"。
 *
 * AgentPool 返回 Flow<StreamResult>，消费者（AgentRuntime）
 * 通过模式匹配处理不同结果，避免脆弱的字符串前缀检测。
 */
sealed interface StreamResult {
    /** 一个流式 token 片段 */
    data class Token(val text: String) : StreamResult

    /** 流式错误 — 类型化，携带原始消息 */
    data class Error(val type: ErrorType, val message: String) : StreamResult

    /** 流结束标记 */
    data object Done : StreamResult
}

enum class ErrorType {
    /** 计费余额不足 — 不可重试 */
    BILLING,
    /** 网络/连接错误 — 可重试 */
    NETWORK,
    /** 速率限制 — 已在内部重试后仍失败 */
    RATE_LIMIT,
    /** 未知错误 */
    UNKNOWN,
}
