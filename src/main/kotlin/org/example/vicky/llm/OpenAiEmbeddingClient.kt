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
        if (vectors.isNotEmpty() && dimension == -1) dimension = vectors[0].size
        return vectors
    }

    private fun List<Double>.toFloatArray(): FloatArray =
        FloatArray(size) { this[it].toFloat() }
}
