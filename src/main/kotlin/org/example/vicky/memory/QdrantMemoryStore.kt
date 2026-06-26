package org.example.vicky.memory

import org.example.vicky.llm.EmbeddingClient
import org.example.vicky.vector.VectorRecord
import org.example.vicky.vector.VectorStore
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Qdrant 实现的记忆存储。
 */
class QdrantMemoryStore(
    private val vectorStore: VectorStore,
    private val embeddingClient: EmbeddingClient,
    private val collectionName: String = "vicky_memories",
    private val rawCollectionName: String = "vicky_memories_raw",
    private val rawRetentionDays: Long = 30,
    private val distilledRetentionDays: Long = 7,
    private val expiryDays: Long = 90,
) : MemoryStore {

    private var initialized = false

    private suspend fun ensureInitialized() {
        if (initialized) return
        // 必须先确定真实维度，再创建 collection。
        // 旧实现失败时降级到 384，会和真实 embedding 维度不一致，导致后续写入仍然写不进向量。
        if (embeddingClient.dimension == -1) {
            val probe = embeddingClient.embed("initialization")
            check(probe.isNotEmpty()) { "Embedding probe returned empty vector; 无法确定向量维度。" }
        }
        val dim = embeddingClient.dimension
        check(dim > 0) { "Embedding dimension still unknown (=$dim); 拒绝以未知维度创建 collection。" }
        vectorStore.ensureCollection(collectionName, dim)
        vectorStore.ensureCollection(rawCollectionName, dim)
        println("[Vicky] 向量集合已就绪（维度: $dim）")
        initialized = true
    }

    override suspend fun rememberRaw(rawMemory: RawMemory) {
        ensureInitialized()
        val text = "${rawMemory.role}: ${rawMemory.content}"
        val vector = embeddingClient.embed(text)
        val record = VectorRecord(
            id = rawMemory.id,
            vector = vector,
            payload = mapOf(
                "user_id" to rawMemory.userId,
                "conversation_id" to rawMemory.conversationId,
                "role" to rawMemory.role,
                "content" to rawMemory.content,
                "timestamp" to rawMemory.timestamp,
                "turn_index" to rawMemory.turnIndex,
                "distilled" to rawMemory.distilled,
            ),
        )
        vectorStore.upsert(rawCollectionName, listOf(record))
    }

    override suspend fun remember(memory: Memory) {
        ensureInitialized()
        val vector = embeddingClient.embed(memory.content)
        val record = VectorRecord(
            id = memory.id,
            vector = vector,
            payload = mapOf(
                "content" to memory.content,
                "summary" to memory.summary,
                "tags" to memory.tags.joinToString(","),
                "user_id" to (memory.userId ?: ""),
                "source" to memory.source,
                "confidence" to memory.confidence,
                "created_at" to memory.createdAt,
                "distilled_at" to memory.distilledAt,
            ),
        )
        vectorStore.upsert(collectionName, listOf(record))
    }

    override suspend fun recall(query: String, userId: String?, topK: Int): List<Memory> {
        ensureInitialized()
        val vector = embeddingClient.embed(query)
        val filter = buildMap {
            if (userId != null) {
                put("user_id", userId)
            }
        }

        // 同时搜索蒸馏记忆和原始记忆
        val distilledResults = vectorStore.search(collectionName, vector, topK, filter.ifEmpty { null })
        val rawResults = vectorStore.search(rawCollectionName, vector, topK, filter.ifEmpty { null })

        val memories = mutableListOf<Memory>()

        // 转换蒸馏记忆
        for (result in distilledResults) {
            val payload = result.payload
            val content = payload["content"] as? String ?: continue
            val summary = payload["summary"] as? String ?: content
            val tagsStr = payload["tags"] as? String ?: ""
            val tags = if (tagsStr.isBlank()) emptySet() else tagsStr.split(",").toSet()
            val sourceUserId = payload["user_id"] as? String
            val source = payload["source"] as? String ?: "learned"
            val confidence = (payload["confidence"] as? Double)?.toFloat() ?: 1.0f
            val createdAt = (payload["created_at"] as? Double)?.toLong() ?: 0L
            val distilledAt = (payload["distilled_at"] as? Double)?.toLong() ?: 0L
            memories.add(Memory(
                id = result.id,
                content = content,
                summary = summary,
                tags = tags,
                userId = sourceUserId?.ifBlank { null },
                source = source,
                confidence = confidence,
                createdAt = createdAt,
                distilledAt = distilledAt,
            ))
        }

        // 转换原始记忆
        for (result in rawResults) {
            val payload = result.payload
            val content = payload["content"] as? String ?: continue
            val sourceUserId = payload["user_id"] as? String
            val role = payload["role"] as? String ?: "user"
            memories.add(Memory(
                id = result.id,
                content = content,
                summary = content,
                userId = sourceUserId?.ifBlank { null },
                source = "raw_$role",
                confidence = 1.0f,
            ))
        }

        return memories.take(topK)
    }

    override suspend fun recallRaw(query: String, userId: String?, topK: Int): List<RawMemory> {
        ensureInitialized()
        val vector = embeddingClient.embed(query)
        val filter = buildMap {
            if (userId != null) {
                put("user_id", userId)
            }
        }
        val results = vectorStore.search(rawCollectionName, vector, topK, filter.ifEmpty { null })
        return results.mapNotNull { result ->
            val payload = result.payload
            val content = payload["content"] as? String ?: return@mapNotNull null
            val role = payload["role"] as? String ?: "user"
            val userIdValue = payload["user_id"] as? String ?: ""
            val conversationId = payload["conversation_id"] as? String ?: ""
            val timestamp = (payload["timestamp"] as? Double)?.toLong() ?: 0L
            val turnIndex = (payload["turn_index"] as? Double)?.toInt() ?: 0
            val distilled = payload["distilled"] as? Boolean ?: false
            RawMemory(
                id = result.id,
                userId = userIdValue,
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = timestamp,
                turnIndex = turnIndex,
                distilled = distilled,
            )
        }
    }

    override suspend fun forget(id: String) {
        ensureInitialized()
        vectorStore.delete(collectionName, listOf(id))
    }

    override suspend fun forgetRaw(id: String) {
        ensureInitialized()
        vectorStore.delete(rawCollectionName, listOf(id))
    }

    override suspend fun forgetByUser(userId: String) {
        ensureInitialized()
        vectorStore.deleteByFilter(collectionName, mapOf("user_id" to userId))
    }

    override suspend fun getUndistilledRawMemories(userId: String?): List<RawMemory> {
        ensureInitialized()
        val filter = buildMap {
            if (userId != null) {
                put("user_id", userId)
            }
            put("distilled", false)
        }
        val records = vectorStore.scrollPayloadOnly(rawCollectionName, 1000, if (filter.isEmpty()) null else filter)
        return records.map { record ->
            val payload = record.payload
            RawMemory(
                id = record.id,
                userId = payload["user_id"] as? String ?: "",
                conversationId = payload["conversation_id"] as? String ?: "",
                role = payload["role"] as? String ?: "user",
                content = payload["content"] as? String ?: "",
                timestamp = (payload["timestamp"] as? Double)?.toLong() ?: 0L,
                turnIndex = (payload["turn_index"] as? Double)?.toInt() ?: 0,
                distilled = false,
            )
        }
    }

    override suspend fun markAsDistilled(ids: List<String>) {
        ensureInitialized()
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        // 批量获取所有需要标记的记录（一次 HTTP 调用）
        val records = vectorStore.scrollPayloadOnly(rawCollectionName, idSet.size + 100, null)
        val toUpdate = records.filter { it.id in idSet }.map { record ->
            val updatedPayload = record.payload.toMutableMap()
            updatedPayload["distilled"] = true
            // 需要原始向量来 upsert，回退到 scroll 获取
            VectorRecord(record.id, record.vector, updatedPayload)
        }
        if (toUpdate.isEmpty()) return
        // 对于需要向量的 upsert，获取完整记录
        val fullRecords = vectorStore.scroll(rawCollectionName, toUpdate.size + 100)
        val idToVector = fullRecords.associate { it.id to it.vector }
        val finalUpdate = toUpdate.map { rec ->
            val vector = idToVector[rec.id] ?: rec.vector
            VectorRecord(rec.id, vector, rec.payload)
        }
        vectorStore.upsert(rawCollectionName, finalUpdate)
    }

    override suspend fun cleanup() {
        ensureInitialized()
        val now = Instant.now()

        // 清理已蒸馏的原始记忆（保留 7 天）— 仅加载 payload，不加载向量
        val allRaw = vectorStore.scrollPayloadOnly(rawCollectionName, 10000, null)
        val rawToDelete = allRaw.filter { record ->
            val distilled = record.payload["distilled"] as? Boolean ?: false
            val timestamp = (record.payload["timestamp"] as? Double)?.toLong() ?: 0L
            val age = ChronoUnit.DAYS.between(Instant.ofEpochMilli(timestamp), now)
            distilled && age > distilledRetentionDays
        }.map { it.id }
        if (rawToDelete.isNotEmpty()) {
            vectorStore.delete(rawCollectionName, rawToDelete)
        }

        // 清理未蒸馏的原始记忆（保留 30 天）
        val undistilledToDelete = allRaw.filter { record ->
            val distilled = record.payload["distilled"] as? Boolean ?: false
            val timestamp = (record.payload["timestamp"] as? Double)?.toLong() ?: 0L
            val age = ChronoUnit.DAYS.between(Instant.ofEpochMilli(timestamp), now)
            !distilled && age > rawRetentionDays
        }.map { it.id }
        if (undistilledToDelete.isNotEmpty()) {
            vectorStore.delete(rawCollectionName, undistilledToDelete)
        }

        // 清理过期的蒸馏记忆（保留 90 天）
        val allMemories = vectorStore.scrollPayloadOnly(collectionName, 10000, null)
        val memoriesToDelete = allMemories.filter { record ->
            val createdAt = (record.payload["created_at"] as? Double)?.toLong() ?: 0L
            val age = ChronoUnit.DAYS.between(Instant.ofEpochMilli(createdAt), now)
            age > expiryDays
        }.map { it.id }
        if (memoriesToDelete.isNotEmpty()) {
            vectorStore.delete(collectionName, memoriesToDelete)
        }
    }

    override suspend fun getUserMemoryCount(userId: String): Int {
        ensureInitialized()
        val records = vectorStore.scrollPayloadOnly(collectionName, 1000, mapOf("user_id" to userId))
        return records.size
    }
}
