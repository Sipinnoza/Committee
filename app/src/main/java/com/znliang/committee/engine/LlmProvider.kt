package com.znliang.committee.engine

/**
 * LLM 提供商配置
 */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val chatEndpoint: String,       // relative path for chat completions
    val defaultModel: String,
    val keyPlaceholder: String,
    val models: List<String>,
) {
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/",
        chatEndpoint = "v1/messages",
        defaultModel = "claude-sonnet-4-20250514",
        keyPlaceholder = "sk-ant-...",
        models = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229",
        ),
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/",
        chatEndpoint = "chat/completions",
        defaultModel = "deepseek-chat",
        keyPlaceholder = "sk-...",
        models = listOf(
            "deepseek-chat",
            "deepseek-reasoner",
        ),
    ),
    KIMI(
        id = "kimi",
        displayName = "Kimi (Moonshot)",
        defaultBaseUrl = "https://api.moonshot.cn/",
        chatEndpoint = "v1/chat/completions",
        defaultModel = "moonshot-v1-8k",
        keyPlaceholder = "sk-...",
        models = listOf(
            "moonshot-v1-8k",
            "moonshot-v1-32k",
            "moonshot-v1-128k",
            "kimi-k2.5",
        ),
    ),
    ZAI(
        id = "zai",
        displayName = "智谱 Z.AI (GLM)",
        defaultBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4/",
        chatEndpoint = "chat/completions",
        defaultModel = "glm-5.1",
        keyPlaceholder = "your-zai-api-key",
        models = listOf(
            "glm-5.1",
            "glm-5",
            "glm-5-turbo",
            "glm-4.7",
            "glm-4.6",
            "glm-4.5",
            "glm-4-32b-0414-128k",
        ),
    );

    companion object {
        fun fromId(id: String): LlmProvider = entries.find { it.id == id } ?: DEEPSEEK
    }
}

/**
 * 当前 LLM 配置快照
 */
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.DEEPSEEK,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
    val baseUrl: String = provider.defaultBaseUrl,
) {
    val isReady: Boolean get() = apiKey.isNotBlank()

    /** 显示给用户的简要描述 */
    val displayTag: String get() = "${provider.displayName} / $model"
}
