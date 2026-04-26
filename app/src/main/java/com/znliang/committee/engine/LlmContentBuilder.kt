package com.znliang.committee.engine

/**
 * 多模态内容数据，在 AgentPool 调用时传入。
 */
data class MaterialData(
    val mimeType: String,   // 文件MIME类型，如image/jpeg、image/png
    val base64: String,     // base64编码的图片数据
    val fileName: String = "", // 文件名
)

/**
 * 构建 LLM 消息的 content 字段。
 *
 * - 无附件时返回纯 String，保持向后兼容（零开销）。
 * - 有附件时返回 List<Map>，对应 Anthropic 或 OpenAI 的 content block 数组。
 */
object LlmContentBuilder {

    /**
     * 根据 provider 类型构建 content 值。
     * 返回 String（纯文本）或 List<Map<String, Any>>（多模态 content blocks）。
     */
    fun buildContent(
        text: String,
        materials: List<MaterialData>,
        provider: LlmProvider,
    ): Any {
        if (materials.isEmpty()) return text
        return when (provider) {
            LlmProvider.ANTHROPIC -> buildAnthropicContent(text, materials)
            else -> buildOpenAiContent(text, materials)
        }
    }

    /**
     * Anthropic 格式：
     * ```json
     * [
     *   {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "..."}},
     *   {"type": "text", "text": "用户消息"}
     * ]
     * ```
     */
    private fun buildAnthropicContent(
        text: String,
        materials: List<MaterialData>,
    ): List<Map<String, Any>> {
        val blocks = mutableListOf<Map<String, Any>>()
        for (mat in materials) {
            if (!mat.mimeType.startsWith("image/")) continue
            blocks.add(mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to mat.mimeType,
                    "data" to mat.base64,
                ),
            ))
        }
        blocks.add(mapOf("type" to "text", "text" to text))
        return blocks
    }

    /**
     * OpenAI 兼容格式（DeepSeek / Kimi / ZAI）：
     * ```json
     * [
     *   {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}},
     *   {"type": "text", "text": "用户消息"}
     * ]
     * ```
     */
    private fun buildOpenAiContent(
        text: String,
        materials: List<MaterialData>,
    ): List<Map<String, Any>> {
        val blocks = mutableListOf<Map<String, Any>>()
        for (mat in materials) {
            if (!mat.mimeType.startsWith("image/")) continue
            blocks.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:${mat.mimeType};base64,${mat.base64}",
                ),
            ))
        }
        blocks.add(mapOf("type" to "text", "text" to text))
        return blocks
    }
}
