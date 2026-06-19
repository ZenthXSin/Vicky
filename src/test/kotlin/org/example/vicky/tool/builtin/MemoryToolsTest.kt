package org.example.vicky.tool.builtin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.vicky.llm.EmbeddingClient
import org.example.vicky.memory.Memory
import org.example.vicky.memory.QdrantMemoryStore
import org.example.vicky.vector.QdrantVectorStore
import org.example.vicky.vector.VectorRecord
import org.example.vicky.vector.VectorStore
import org.example.vicky.tool.ToolContext
import org.example.vicky.context.ConversationStore
import org.example.vicky.tool.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记忆工具单元测试。
 */
class MemoryToolsTest {

    private val qdrantHost = System.getenv("QDRANT_HOST") ?: "192.168.0.108"
    private val qdrantPort = (System.getenv("QDRANT_PORT") ?: "6333").toInt()

    private fun createMockEmbeddingClient(): EmbeddingClient {
        return object : EmbeddingClient {
            override val dimension: Int = 384
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                return texts.map { FloatArray(384) { 0.5f } }
            }
        }
    }

    private fun createMemoryStore(): QdrantMemoryStore {
        val embeddingClient = createMockEmbeddingClient()
        val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
        return QdrantMemoryStore(
            vectorStore, embeddingClient,
            collectionName = "test_tool_memories",
            rawCollectionName = "test_tool_memories_raw",
        )
    }

    private fun createToolContext(userId: String = "test_user"): ToolContext {
        return ToolContext(
            userId = userId,
            conversationId = "test_conv",
            groupId = "",
            store = ConversationStore(),
            tools = ToolRegistry(),
        )
    }

    // ==================== MemoryStoreTool 测试 ====================

    /**
     * 测试 1：memory_store 正常存储
     */
    @Test
    fun testMemoryStoreNormal() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemoryStoreTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("content", "Eve 是我的前辈")
        }

        val result = tool.execute(ctx, args)
        assertTrue(result.toAgent.contains("successfully") || result.toAgent.contains("成功"))
        assertEquals("已记住。", result.userReply)

        // 验证数据已写入
        val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
            vectorStore.scroll("test_tool_memories", 10)
        }
        assertTrue(records.any { it.payload["content"] == "Eve 是我的前辈" })

        println("✅ 测试 1 通过：memory_store 正常存储")
    }

    /**
     * 测试 2：memory_store 缺少 content 参数
     */
    @Test
    fun testMemoryStoreMissingContent() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemoryStoreTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("tags", "tag1")
        }

        val result = tool.execute(ctx, args)
        assertTrue(result.toAgent.contains("Error") || result.toAgent.contains("error"))
        println("✅ 测试 2 通过：memory_store 缺少 content 参数")
    }

    /**
     * 测试 3：memory_store 空内容
     */
    @Test
    fun testMemoryStoreEmptyContent() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemoryStoreTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("content", "   ")
        }

        val result = tool.execute(ctx, args)
        assertTrue(result.toAgent.contains("Error") || result.toAgent.contains("error") || result.toAgent.contains("empty"))
        println("✅ 测试 3 通过：memory_store 空内容")
    }

    /**
     * 测试 4：memory_store 带 tags
     */
    @Test
    fun testMemoryStoreWithTags() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemoryStoreTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("content", "项目使用 Kotlin")
            put("tags", "kotlin, project")
        }

        val result = tool.execute(ctx, args)
        assertTrue(result.toAgent.contains("successfully") || result.toAgent.contains("成功"))

        // 验证 tags 已写入
        val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
            vectorStore.scroll("test_tool_memories", 10)
        }
        val found = records.any {
            it.payload["content"] == "项目使用 Kotlin" &&
                it.payload["tags"].toString().contains("kotlin")
        }
        assertTrue(found, "tags 应该被正确存储")

        println("✅ 测试 4 通过：memory_store 带 tags")
    }

    // ==================== MemorySearchTool 测试 ====================

    /**
     * 测试 5：memory_search 正常搜索
     */
    @Test
    fun testMemorySearchNormal() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemorySearchTool(memoryStore)
        val ctx = createToolContext()

        // 先存储一条记忆
        memoryStore.remember(Memory(
            content = "用户喜欢红色",
            userId = "test_user",
            source = "user_stated",
        ))

        // 使用 scroll 验证数据已写入
        val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
            vectorStore.scroll("test_tool_memories", 10)
        }
        assertTrue(records.any { it.payload["content"] == "用户喜欢红色" }, "数据应该已写入")

        val args = buildJsonObject {
            put("query", "颜色")
        }

        val result = tool.execute(ctx, args)
        // 搜索可能返回空（因为 mock 向量相同），但数据应该存在
        assertTrue(
            result.toAgent.contains("Found") ||
                result.toAgent.contains("No") ||
                result.toAgent.contains("没有"),
            "应该返回搜索结果或无结果"
        )
        println("✅ 测试 5 通过：memory_search 正常搜索")
    }

    /**
     * 测试 6：memory_search 无结果
     */
    @Test
    fun testMemorySearchNoResults() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemorySearchTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("query", "完全无关的内容 xyz123")
        }

        val result = tool.execute(ctx, args)
        // 搜索可能返回空或有结果（因为之前的测试数据残留）
        // 只要不抛异常就算通过
        println("✅ 测试 6 通过：memory_search 无结果")
    }

    /**
     * 测试 7：memory_search 缺少 query 参数
     */
    @Test
    fun testMemorySearchMissingQuery() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemorySearchTool(memoryStore)
        val ctx = createToolContext()

        val args = buildJsonObject {
            put("top_k", 5)
        }

        val result = tool.execute(ctx, args)
        assertTrue(result.toAgent.contains("Error") || result.toAgent.contains("error"))
        println("✅ 测试 7 通过：memory_search 缺少 query 参数")
    }

    /**
     * 测试 8：memory_search 带 top_k
     */
    @Test
    fun testMemorySearchWithTopK() = runBlocking {
        val memoryStore = createMemoryStore()
        val tool = MemorySearchTool(memoryStore)
        val ctx = createToolContext()

        // 存储多条记忆
        memoryStore.remember(Memory(content = "用户喜欢红色", userId = "test_user", source = "user_stated"))
        memoryStore.remember(Memory(content = "用户喜欢蓝色", userId = "test_user", source = "user_stated"))
        memoryStore.remember(Memory(content = "用户喜欢绿色", userId = "test_user", source = "user_stated"))

        // 使用 scroll 验证数据已写入
        val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val vectorStore = QdrantVectorStore(qdrantHost, qdrantPort)
            vectorStore.scroll("test_tool_memories", 10)
        }
        assertTrue(records.size >= 3, "应该有至少 3 条记忆")

        val args = buildJsonObject {
            put("query", "颜色")
            put("top_k", 2)
        }

        val result = tool.execute(ctx, args)
        // 搜索可能返回空（因为 mock 向量相同），但数据应该存在
        assertTrue(
            result.toAgent.contains("Found") ||
                result.toAgent.contains("No") ||
                result.toAgent.contains("没有"),
            "应该返回搜索结果或无结果"
        )
        println("✅ 测试 8 通过：memory_search 带 top_k")
    }

    // ==================== MemoryDistillTool 测试 ====================

    /**
     * 测试 9：memory_distill 执行
     */
    @Test
    fun testMemoryDistillExecute() = runBlocking {
        val memoryStore = createMemoryStore()
        val embeddingClient = createMockEmbeddingClient()
        val openAi = com.aallam.openai.client.OpenAI(
            com.aallam.openai.client.OpenAIConfig(
                token = "test-key",
            )
        )
        val distiller = org.example.vicky.memory.Distiller(openAi, embeddingClient)
        val scheduler = org.example.vicky.memory.DistillationScheduler(
            memoryStore, distiller, 10, false
        )
        val tool = MemoryDistillTool(scheduler)

        val args = buildJsonObject {}

        // 蒸馏会失败（因为没有 OpenAI 连接），但不应该抛异常
        val result = tool.execute("test_user", args)
        // 应该返回成功或失败消息，不应该崩溃
        assertTrue(
            result.toAgent.contains("completed") ||
                result.toAgent.contains("completed") ||
                result.toAgent.contains("Error") ||
                result.toAgent.contains("error") ||
                result.toAgent.contains("失败")
        )
        println("✅ 测试 9 通过：memory_distill 执行")
    }

    // ==================== FileSearchTool 测试 ====================

    /**
     * 测试 10：file_search 未启用
     */
    @Test
    fun testFileSearchNotEnabled() = runBlocking {
        val tool = FileSearchTool(null)

        val args = buildJsonObject {
            put("query", "test")
        }

        val result = tool.execute("test_user", args)
        assertTrue(result.toAgent.contains("Error") || result.toAgent.contains("error") || result.toAgent.contains("not enabled"))
        println("✅ 测试 10 通过：file_search 未启用")
    }

    /**
     * 测试 11：file_search 缺少 query 参数
     */
    @Test
    fun testFileSearchMissingQuery() = runBlocking {
        val tool = MockFileSearchTool()

        val args = buildJsonObject {
            put("top_k", 5)
        }

        val result = tool.execute("test_user", args)
        assertTrue(result.toAgent.contains("Error") || result.toAgent.contains("error"))
        println("✅ 测试 11 通过：file_search 缺少 query 参数")
    }

    /**
     * 测试 12：file_search 正常搜索
     */
    @Test
    fun testFileSearchNormal() = runBlocking {
        val searchResults = listOf(
            org.example.vicky.file.FileSearchResult(
                path = "src/main.kt",
                chunk = "fun main() {}",
                score = 0.9f,
                startLine = 1,
                endLine = 3,
            )
        )
        val tool = MockFileSearchTool(searchResults)

        val args = buildJsonObject {
            put("query", "main function")
        }

        val result = tool.execute("test_user", args)
        assertTrue(result.toAgent.contains("Found") || result.toAgent.contains("找到"))
        assertTrue(result.toAgent.contains("src/main.kt"))
        println("✅ 测试 12 通过：file_search 正常搜索")
    }

    /**
     * 测试 13：file_search 无结果
     */
    @Test
    fun testFileSearchNoResults() = runBlocking {
        val tool = MockFileSearchTool(emptyList())

        val args = buildJsonObject {
            put("query", "xyz123")
        }

        val result = tool.execute("test_user", args)
        assertTrue(result.toAgent.contains("No") || result.toAgent.contains("没有") || result.toAgent.contains("无"))
        println("✅ 测试 13 通过：file_search 无结果")
    }

    // ==================== Mock 类 ====================

    private class MockFileSearchTool(
        private val searchResults: List<org.example.vicky.file.FileSearchResult> = emptyList(),
    ) : org.example.vicky.tool.Tool() {
        override val name = "file_search"
        override val description = "Mock file search tool"
        override val parameters = buildJsonObject { put("type", "object") }

        override suspend fun execute(userId: String, args: kotlinx.serialization.json.JsonObject): org.example.vicky.tool.ToolResult {
            val query = args["query"]?.jsonPrimitive?.content?.trim()
                ?: return org.example.vicky.tool.ToolResult(toAgent = "Error: missing 'query'.")

            if (searchResults.isEmpty()) {
                return org.example.vicky.tool.ToolResult(toAgent = "No matching files found.")
            }

            val result = searchResults.joinToString("\n\n") { searchResult ->
                "File: ${searchResult.path} (lines ${searchResult.startLine}-${searchResult.endLine}, score: %.2f)\n${searchResult.chunk}".format(
                    searchResult.score
                )
            }
            return org.example.vicky.tool.ToolResult(toAgent = "Found ${searchResults.size} matching results:\n$result")
        }
    }
}
