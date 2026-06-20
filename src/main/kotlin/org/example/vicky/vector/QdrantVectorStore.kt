package org.example.vicky.vector

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Qdrant REST API 实现。
 */
class QdrantVectorStore(
    private val host: String = "localhost",
    private val httpPort: Int = 6333,
) : VectorStore {

    private val baseUrl = "http://$host:$httpPort"
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    override suspend fun upsert(collection: String, records: List<VectorRecord>) {
        if (records.isEmpty()) return
        // 分批发送，避免单次请求超出 Qdrant 32MB 限制
        records.chunked(100).forEach { batch -> upsertBatch(collection, batch) }
    }

    private suspend fun upsertBatch(collection: String, records: List<VectorRecord>) {
        withContext(Dispatchers.IO) {
            val points = records.map { record ->
                buildJsonObject {
                    put("id", record.id)
                    put("vector", kotlinx.serialization.json.JsonArray(record.vector.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("payload", buildJsonObject {
                        record.payload.forEach { (key, value) ->
                            put(key, toJsonElement(value))
                        }
                    })
                }
            }
            val body = buildJsonObject {
                put("points", kotlinx.serialization.json.JsonArray(points))
            }

            val maxRetries = 3
            var lastException: Exception? = null
            repeat(maxRetries) { attempt ->
                try {
                    val response: HttpResponse = client.put("$baseUrl/collections/$collection/points") {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                    response.body<String>()  // 消费响应体以释放连接
                    if (response.status.isSuccess()) {
                        return@withContext
                    }
                    val errorMsg = response.body<String>()
                    if (attempt < maxRetries - 1) {
                        val delayMs = (attempt + 1) * 1000L
                        println("[Vicky] Qdrant upsert 失败 (${response.status})，${delayMs}ms 后重试 (${attempt + 1}/$maxRetries)...")
                        delay(delayMs)
                    } else {
                        throw RuntimeException("Qdrant upsert failed after $maxRetries attempts: ${response.status} - $errorMsg")
                    }
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        val delayMs = (attempt + 1) * 1000L
                        println("[Vicky] Qdrant upsert 异常: ${e.message}，${delayMs}ms 后重试 (${attempt + 1}/$maxRetries)...")
                        delay(delayMs)
                    }
                }
            }
            throw lastException ?: RuntimeException("Qdrant upsert failed after $maxRetries attempts")
        }
    }

    override suspend fun search(
        collection: String,
        vector: FloatArray,
        topK: Int,
        filter: Map<String, Any>?,
    ): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("vector", kotlinx.serialization.json.JsonArray(vector.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("limit", topK)
                put("with_payload", true)
                filter?.let { put("filter", buildFilterJson(it)) }
            }
            val response = client.post("$baseUrl/collections/$collection/points/search") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.body<String>()
                println("[Vicky] Qdrant 搜索失败: ${response.status} - $errorBody")
                return@withContext emptyList()
            }
            val result = json.parseToJsonElement(response.body<String>()).jsonObject
            val results = result["result"]?.jsonArray ?: return@withContext emptyList()
            results.map { element ->
                val obj = element.jsonObject
                SearchResult(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    score = obj["score"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    payload = obj["payload"]?.jsonObject?.mapValues { (_, v) -> fromJsonElement(v) } ?: emptyMap(),
                )
            }
        }
    }

    override suspend fun delete(collection: String, ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("points", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            client.post("$baseUrl/collections/$collection/points/delete") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body<String>()
        }
    }

    override suspend fun deleteByFilter(collection: String, filter: Map<String, Any>) {
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("filter", buildFilterJson(filter))
            }
            client.post("$baseUrl/collections/$collection/points/delete") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body<String>()
        }
    }

    override suspend fun scroll(
        collection: String,
        limit: Int,
        filter: Map<String, Any>?,
    ): List<VectorRecord> {
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("limit", limit)
                filter?.let { put("filter", buildFilterJson(it)) }
            }
            val response = client.post("$baseUrl/collections/$collection/points/scroll") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val result = json.parseToJsonElement(response.body<String>()).jsonObject
            val resultObj = result["result"]?.jsonObject
            val resultPoints = resultObj?.get("points")?.jsonArray ?: return@withContext emptyList()
            resultPoints.map { element ->
                val obj = element.jsonObject
                VectorRecord(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    vector = obj["vector"]?.jsonArray
                        ?.map { it.jsonPrimitive.content.toFloat() }
                        ?.toFloatArray() ?: floatArrayOf(),
                    payload = obj["payload"]?.jsonObject?.mapValues { (_, v) -> fromJsonElement(v) } ?: emptyMap(),
                )
            }
        }
    }

    /** 仅加载 payload，不返回向量数据。用于只需要元数据的场景（cleanup、缓存加载等）。 */
    override suspend fun scrollPayloadOnly(
        collection: String,
        limit: Int,
        filter: Map<String, Any>?,
    ): List<VectorRecord> {
        return withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("limit", limit)
                put("with_vectors", false)
                filter?.let { put("filter", buildFilterJson(it)) }
            }
            val response = client.post("$baseUrl/collections/$collection/points/scroll") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val result = json.parseToJsonElement(response.body<String>()).jsonObject
            val resultObj = result["result"]?.jsonObject
            val resultPoints = resultObj?.get("points")?.jsonArray ?: return@withContext emptyList()
            resultPoints.map { element ->
                val obj = element.jsonObject
                VectorRecord(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    vector = floatArrayOf(),
                    payload = obj["payload"]?.jsonObject?.mapValues { (_, v) -> fromJsonElement(v) } ?: emptyMap(),
                )
            }
        }
    }

    override suspend fun ensureCollection(collection: String, dimension: Int) {
        withContext(Dispatchers.IO) {
            // 检查 collection 是否存在
            val response = client.get("$baseUrl/collections/$collection")
            val responseStr = response.body<String>()
            if (response.status.value == 200) {
                // 检查维度是否匹配
                val result = json.parseToJsonElement(responseStr).jsonObject
                val resultObj = result["result"]?.jsonObject
                val vectorsConfig = resultObj?.get("config")?.jsonObject?.get("params")?.jsonObject?.get("vectors")?.jsonObject
                val existingDim = vectorsConfig?.get("size")?.toString()?.toIntOrNull()

                if (existingDim != null && existingDim != dimension) {
                    println("[Vicky] Collection '$collection' 维度不匹配: 期望 $dimension, 实际 $existingDim，重新创建")
                    // 删除旧 collection
                    client.delete("$baseUrl/collections/$collection").body<String>()
                    // 重新创建
                    val body = buildJsonObject {
                        putJsonObject("vectors") {
                            put("size", dimension)
                            put("distance", "Cosine")
                        }
                    }
                    client.put("$baseUrl/collections/$collection") {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }.body<String>()
                }
                return@withContext
            }

            // 创建新 collection
            val body = buildJsonObject {
                putJsonObject("vectors") {
                    put("size", dimension)
                    put("distance", "Cosine")
                }
            }
            client.put("$baseUrl/collections/$collection") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.body<String>()
        }
    }

    private fun buildFilterJson(filter: Map<String, Any>): JsonObject {
        val conditions = filter.map { (key, value) ->
            buildJsonObject {
                put("key", key)
                putJsonObject("match") {
                    put("value", value.toString())
                }
            }
        }
        return buildJsonObject {
            put("must", kotlinx.serialization.json.JsonArray(conditions))
        }
    }

    private fun toJsonElement(value: Any): kotlinx.serialization.json.JsonElement = when (value) {
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value.toDouble())
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it ?: "") })
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }

    private fun fromJsonElement(element: kotlinx.serialization.json.JsonElement): Any = when {
        element is kotlinx.serialization.json.JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                else -> element.content.toDoubleOrNull() ?: element.content
            }
        }
        element is kotlinx.serialization.json.JsonArray -> element.map { fromJsonElement(it) }
        else -> element.toString()
    }

    fun close() {
        client.close()
    }
}
