package org.example.vicky.llm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.vicky.agent.EmbeddingConfig

class OpenAiEmbeddingClient(private val config: EmbeddingConfig) : EmbeddingClient {

    @Volatile
    override var dimension: Int = -1
        private set

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    @Serializable
    private data class Req(val model: String, val input: List<String>)

    @Serializable
    private data class EmbData(val embedding: List<Double>)

    @Serializable
    private data class Resp(val data: List<EmbData>)

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val base = if (config.baseUrl.endsWith("/")) config.baseUrl else "${config.baseUrl}/"
        val resp = client.post("${base}embeddings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(Req(config.model, texts))
        }.body<Resp>()
        val vectors = resp.data.map { it.embedding.toFloatArray() }
        check(vectors.size == texts.size) {
            "Embedding endpoint returned ${vectors.size} vectors for ${texts.size} inputs (model=${config.model}, baseUrl=${config.baseUrl})"
        }
        val firstEmpty = vectors.indexOfFirst { it.isEmpty() }
        check(firstEmpty < 0) { "Embedding endpoint returned empty vector at index $firstEmpty" }
        if (dimension == -1) dimension = vectors[0].size
        return vectors
    }

    private fun List<Double>.toFloatArray() = FloatArray(size) { this[it].toFloat() }
}
