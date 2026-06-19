package org.example.vicky.llm

/**
 * 语义向量客户端。屏蔽内置（本地推理）与外置（OpenAI 协议）差异。
 *
 * 实现要求线程安全：可能被多个协程同时调用。
 */
interface EmbeddingClient {
    /** 已知向量维度；首次调用前可能为 -1，调用后回填。 */
    val dimension: Int

    /** 批量编码。返回顺序与入参对应。 */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /** 单条便捷重载。 */
    suspend fun embed(text: String): FloatArray = embed(listOf(text)).first()
}
