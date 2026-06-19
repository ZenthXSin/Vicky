package org.example.vicky.llm

import org.example.vicky.agent.EmbeddingConfig

/**
 * 根据 [EmbeddingConfig] 创建对应的 [EmbeddingClient]。
 * - [EmbeddingConfig.Builtin]  -> [BuiltinEmbeddingClient]（本地 DJL 推理）
 * - [EmbeddingConfig.External] -> [OpenAiEmbeddingClient]（OpenAI 协议远程端点）
 */
object EmbeddingClientFactory {
    fun create(config: EmbeddingConfig): EmbeddingClient = when (config) {
        is EmbeddingConfig.Builtin  -> BuiltinEmbeddingClient(config)
        is EmbeddingConfig.External -> OpenAiEmbeddingClient(config)
    }
}
