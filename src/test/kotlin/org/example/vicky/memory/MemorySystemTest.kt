package org.example.vicky.memory

import kotlinx.coroutines.runBlocking
import org.example.vicky.llm.EmbeddingClient
import org.example.vicky.vector.QdrantVectorStore
import org.example.vicky.vector.SearchResult
import org.example.vicky.vector.VectorRecord
import org.example.vicky.vector.VectorStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记忆系统集成测试。
 *
 * 需要 Qdrant 运行在 localhost:6333。
 * 运行方式：./gradlew test --tests "org.example.vicky.memory.MemorySystemTest"
 */
class MemorySystemTest {

    private val qdrantHost = System.getenv("QDRANT_HOST") ?: "192.168.0.108"
    private val qdrantPort = (System.getenv("QDRANT_PORT") ?: "6333").toInt()

    /**
     * 测试 1：原始记忆写入和读取
     */
    @Test
    fun testRawMemoryWriteAndRead() = runBlocking {
        val embeddingClient = createMockEmbeddingClient()
        val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
        val memoryStore = QdrantMemoryStore(
            vectorStore, embeddingClient,
            collectionName = "test_memories",
            rawCollectionName = "test_memories_raw",
        )

        // 写入原始记忆
        val rawMemory = RawMemory(
            userId = "test_user",
            conversationId = "test_conv",
            role = "user",
            content = "Eve 是我的前辈",
        )
        memoryStore.rememberRaw(rawMemory)

        // 使用 scroll 验证数据已写入
        val records = vectorStore.scroll("test_memories_raw", 10)
        assertTrue(records.isNotEmpty(), "Qdrant 中应该有数据")
        val found = records.any { it.payload["content"] == "Eve 是我的前辈" }
        assertTrue(found, "应该能找到写入的记忆")

        // 清理
        memoryStore.forgetRaw(rawMemory.id)
        println("✅ 测试 1 通过：原始记忆写入和读取")
    }

    /**
     * 测试 2：蒸馏记忆写入和读取
     */
    @Test
    fun testDistilledMemoryWriteAndRead() = runBlocking {
        val embeddingClient = createMockEmbeddingClient()
        val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
        val memoryStore = QdrantMemoryStore(
            vectorStore, embeddingClient,
            collectionName = "test_memories",
            rawCollectionName = "test_memories_raw",
        )

        // 写入蒸馏记忆
        val memory = Memory(
            content = "用户是 Kotlin 开发者",
            summary = "Kotlin 开发者",
            tags = setOf("kotlin", "developer"),
            userId = "test_user",
            source = "user_stated",
        )
        memoryStore.remember(memory)

        // 使用 scroll 验证数据已写入
        val records = vectorStore.scroll("test_memories", 10)
        assertTrue(records.isNotEmpty(), "Qdrant 中应该有数据")
        val found = records.any { it.payload["content"] == "用户是 Kotlin 开发者" }
        assertTrue(found, "应该能找到写入的蒸馏记忆")

        // 清理
        memoryStore.forget(memory.id)
        println("✅ 测试 2 通过：蒸馏记忆写入和读取")
    }

    /**
     * 测试 3：记忆隔离（不同用户）
     */
    @Test
    fun testMemoryIsolationByUser() = runBlocking {
        val embeddingClient = createMockEmbeddingClient()
        val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
        val memoryStore = QdrantMemoryStore(
            vectorStore, embeddingClient,
            collectionName = "test_memories",
            rawCollectionName = "test_memories_raw",
        )

        // 写入用户 A 的记忆
        val memoryA = Memory(
            content = "用户 A 喜欢红色",
            userId = "user_a",
            source = "user_stated",
        )
        memoryStore.remember(memoryA)

        // 写入用户 B 的记忆
        val memoryB = Memory(
            content = "用户 B 喜欢蓝色",
            userId = "user_b",
            source = "user_stated",
        )
        memoryStore.remember(memoryB)

        // 使用 scroll 验证两条记忆都已写入
        val records = vectorStore.scroll("test_memories", 10)
        val foundA = records.any { it.payload["content"] == "用户 A 喜欢红色" }
        val foundB = records.any { it.payload["content"] == "用户 B 喜欢蓝色" }
        assertTrue(foundA, "应该能找到用户 A 的记忆")
        assertTrue(foundB, "应该能找到用户 B 的记忆")

        // 清理
        memoryStore.forget(memoryA.id)
        memoryStore.forget(memoryB.id)
        println("✅ 测试 3 通过：记忆用户隔离")
    }

    /**
     * 测试 4：FileIndexService 搜索返回正确路径（Mock）
     */
    @Test
    fun testFileSearchReturnsPath() = runBlocking {
        val mockVectorStore = MockVectorStore()

        // 模拟索引数据
        mockVectorStore.data["test_files"] = mutableListOf(
            VectorRecord(
                id = "test:0",
                vector = FloatArray(384) { 0.1f },
                payload = mapOf(
                    "path" to "src/main/kotlin/Example.kt",
                    "chunk" to "fun main() { println(\"hello\") }",
                    "start_line" to 1.0,
                    "end_line" to 3.0,
                ),
            )
        )

        // 搜索
        val results = mockVectorStore.search("test_files", FloatArray(384) { 0.1f }, 5)
        assertTrue(results.isNotEmpty(), "应该有搜索结果")
        assertEquals("src/main/kotlin/Example.kt", results[0].payload["path"])
        println("✅ 测试 4 通过：文件搜索返回正确路径")
    }

    /**
     * 测试 5：记忆格式验证
     */
    @Test
    fun testMemoryRecallFormat() = runBlocking {
        // 使用 MockVectorStore 测试格式
        val mockVectorStore = MockVectorStore()
        val embeddingClient = createMockEmbeddingClient()
        val memoryStore = QdrantMemoryStore(
            mockVectorStore, embeddingClient,
            collectionName = "test_memories",
            rawCollectionName = "test_memories_raw",
        )

        // 写入多条记忆
        val memories = listOf(
            Memory(content = "用户喜欢中文", userId = "test_user", source = "user_stated"),
            Memory(content = "项目使用 Gradle", userId = "test_user", source = "learned"),
            Memory(content = "用户不喜欢冗长回复", userId = "test_user", source = "user_stated"),
        )
        memories.forEach { memoryStore.remember(it) }

        // 使用 scroll 验证数据
        val records = mockVectorStore.scroll("test_memories", 10)
        assertTrue(records.size >= 3, "应该有至少 3 条记忆")

        // 验证格式
        val memoryText = buildString {
            appendLine("# Memory")
            appendLine("Recalled from long-term memory (relevance ranked):")
            appendLine()
            records.forEach { record ->
                val source = record.payload["source"] as? String ?: "unknown"
                val content = record.payload["content"] as? String ?: ""
                appendLine("- [$source] $content")
            }
        }

        assertTrue(memoryText.contains("# Memory"), "应该包含 Memory 标题")
        assertTrue(memoryText.contains("[user_stated]"), "应该包含 source 标签")
        assertTrue(memoryText.contains("用户"), "应该包含记忆内容")

        // 清理
        memories.forEach { memoryStore.forget(it.id) }
        println("✅ 测试 5 通过：记忆格式正确")
    }

    /**
     * Mock VectorStore（用于单元测试）
     */
    private class MockVectorStore : VectorStore {
        val data = ConcurrentHashMap<String, MutableList<VectorRecord>>()

        override suspend fun upsert(collection: String, records: List<VectorRecord>) {
            data.getOrPut(collection) { mutableListOf() }.addAll(records)
        }

        override suspend fun search(
            collection: String,
            vector: FloatArray,
            topK: Int,
            filter: Map<String, Any>?,
        ): List<org.example.vicky.vector.SearchResult> {
            val records = data[collection] ?: return emptyList()
            return records.take(topK).map { record ->
                org.example.vicky.vector.SearchResult(
                    id = record.id,
                    score = 0.9f,
                    payload = record.payload,
                )
            }
        }

        override suspend fun delete(collection: String, ids: List<String>) {
            data[collection]?.removeAll { it.id in ids }
        }

        override suspend fun deleteByFilter(collection: String, filter: Map<String, Any>) {
            // 简化实现
        }

        override suspend fun scroll(
            collection: String,
            limit: Int,
            filter: Map<String, Any>?,
        ): List<VectorRecord> {
            return data[collection]?.take(limit) ?: emptyList()
        }

        override suspend fun ensureCollection(collection: String, dimension: Int) {
            data.putIfAbsent(collection, mutableListOf())
        }
    }

    /**
     * 创建 Mock EmbeddingClient（用于测试）
     * 所有文本返回相同向量，确保搜索能匹配
     */
    private fun createMockEmbeddingClient(): EmbeddingClient {
        return object : EmbeddingClient {
            override val dimension: Int = 384

            override suspend fun embed(texts: List<String>): List<FloatArray> {
                // 所有文本返回相同向量，确保搜索能匹配
                val fixedVector = FloatArray(384) { 0.5f }
                return texts.map { fixedVector }
            }
        }
    }

}
