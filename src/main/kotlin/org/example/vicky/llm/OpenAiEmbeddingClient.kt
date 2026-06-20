package org.example.vicky.llm

import com.aallam.openai.api.embedding.EmbeddingRequest
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import org.example.vicky.agent.EmbeddingConfig
import kotlin.time.Duration.Companion.seconds

/**
 * 外置 embedding：走 OpenAI 协议兼容端点。
 *
 * 与主 LLM 的 [OpenAiClientFactory] 解耦——baseUrl/apiKey 各自配置，互不污染。
 */
class OpenAiEmbeddingClient(private val config: EmbeddingConfig) : EmbeddingClient {

    @Volatile
    override var dimension: Int = -1
        private set

    private val client: OpenAI = run {
        // OpenAIHost 用 baseUrl 解析相对路径，必须以 '/' 结尾
        val normalized = if (config.baseUrl.endsWith("/")) config.baseUrl else "${config.baseUrl}/"
        OpenAI(
            OpenAIConfig(
                token = config.apiKey,
                timeout = Timeout(socket = 60.seconds),
                host = OpenAIHost(baseUrl = normalized),
                logging = LoggingConfig(logLevel = LogLevel.None),
            )
        )
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val resp = client.embeddings(
            EmbeddingRequest(
                model = ModelId(config.model),
                input = texts,
            )
        )
        val vectors = resp.embeddings.map { it.embedding.toFloatArray() }
        // 防御：embedding 端点协议不兼容时可能返回空响应或零长度向量，
        // 静默写入会导致 Qdrant 存 payload 但不建向量索引，搜索全空。提前抛错好定位。
        check(vectors.size == texts.size) {
            "Embedding endpoint returned ${vectors.size} vectors for ${texts.size} inputs (model=${config.model}, baseUrl=${config.baseUrl}). 端点协议可能不兼容。"
        }
        val firstEmpty = vectors.indexOfFirst { it.isEmpty() }
        check(firstEmpty < 0) {
            "Embedding endpoint returned empty vector at index $firstEmpty (model=${config.model}). 端点协议可能不兼容。"
        }
        if (dimension == -1) dimension = vectors[0].size
        return vectors
    }

    private fun List<Double>.toFloatArray(): FloatArray =
        FloatArray(size) { this[it].toFloat() }
}
