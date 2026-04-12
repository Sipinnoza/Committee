package com.committee.investing.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// ─── Anthropic /v1/messages ────────────────────────────────────────────────

data class AnthropicRequest(
    val model: String = "claude-sonnet-4-20250514",
    @SerializedName("max_tokens") val maxTokens: Int = 2048,
    val system: String,
    val messages: List<AnthropicMessage>,
)

data class AnthropicMessage(
    val role: String,   // "user" | "assistant"
    val content: String,
)

data class AnthropicResponse(
    val id: String,
    val content: List<ContentBlock>,
    val model: String,
    val usage: Usage,
) {
    val text: String get() = content
        .filter { it.type == "text" }
        .joinToString("") { it.text }
}

data class ContentBlock(
    val type: String,
    val text: String,
)

data class Usage(
    @SerializedName("input_tokens") val inputTokens: Int,
    @SerializedName("output_tokens") val outputTokens: Int,
)

interface AnthropicApiService {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: AnthropicRequest,
    ): AnthropicResponse
}

// ─── OpenAI-compatible /v1/chat/completions (DeepSeek, etc.) ───────────────

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
    @POST("{path}")
    suspend fun createChatCompletion(
        @Path("path") path: String,
        @Header("Authorization") authorization: String,  // "Bearer sk-..."
        @Body request: OpenAiRequest,
    ): OpenAiResponse
}
