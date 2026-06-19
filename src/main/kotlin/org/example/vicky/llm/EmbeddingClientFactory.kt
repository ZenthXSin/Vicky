package org.example.vicky.llm

import org.example.vicky.agent.EmbeddingConfig

/**
 * 根据 [EmbeddingConfig] 创建对应的 [EmbeddingClient]。
 * 使用 OpenAI 协议远程端点。
 */
object EmbeddingClientFactory {
    fun create(config: EmbeddingConfig): EmbeddingClient = OpenAiEmbeddingClient(config)
}
