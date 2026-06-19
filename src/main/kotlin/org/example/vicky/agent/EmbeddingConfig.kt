package org.example.vicky.agent

/**
 * 语义模型（embedding）配置。
 *
 * 外置 OpenAI 协议兼容端点。
 */
data class EmbeddingConfig(
    /** 例如 "https://api.openai.com/v1/"。 */
    val baseUrl: String,
    /** API key。 */
    val apiKey: String,
    /** 模型 id，例如 "text-embedding-3-small"。 */
    val model: String,
)
