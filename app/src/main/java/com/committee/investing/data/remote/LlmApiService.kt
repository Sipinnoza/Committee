package com.committee.investing.data.remote

import com.google.gson.annotations.SerializedName

// ─── OpenAI-compatible /v1/chat/completions (DeepSeek, Kimi, etc.) ────────

data class OpenAiRequest(
    val model: String = "deepseek-chat",
    val messages: List<OpenAiMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 2048,
)

data class OpenAiMessage(
    val role: String,    // "system" | "user" | "assistant"
    val content: String,
)

data class OpenAiResponse(
    val id: String,
    val choices: List<OpenAiChoice>,
    val model: String,
    val usage: OpenAiUsage?,
) {
    val text: String get() = choices.firstOrNull()?.message?.content ?: ""
}

data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @SerializedName("finish_reason") val finishReason: String?,
)

data class OpenAiUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?,
)

interface OpenAiApiService {
    @retrofit2.http.POST("{path}")
    suspend fun createChatCompletion(
        @retrofit2.http.Path("path") path: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @retrofit2.http.Body request: OpenAiRequest,
    ): OpenAiResponse
}
