package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.memory.MemoryStore
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/**
 * 语义搜索记忆。
 */
class MemorySearchTool(
    private val memoryStore: MemoryStore,
) : Tool() {
    override val name = "memory_search"
    override val description =
        "Search long-term memory using semantic similarity. " +
            "Use this to recall previously stored information."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "The search query.")
            }
            putJsonObject("top_k") {
                put("type", "integer")
                put("description", "Number of results to return. Default 5.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'query'.")
        val topK = args["top_k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        val memories = memoryStore.recall(query, userId, topK)
        if (memories.isEmpty()) {
            return ToolResult(toAgent = "No relevant memories found.")
        }

        val result = memories.joinToString("\n") { memory ->
            "- [${memory.source}] ${memory.content}"
        }
        return ToolResult(toAgent = "Found ${memories.size} relevant memories:\n$result")
    }

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'query'.")
        val topK = args["top_k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        val memories = memoryStore.recall(query, ctx.userId, topK)
        if (memories.isEmpty()) {
            return ToolResult(toAgent = "No relevant memories found.")
        }

        val result = memories.joinToString("\n") { memory ->
            "- [${memory.source}] ${memory.content}"
        }
        return ToolResult(toAgent = "Found ${memories.size} relevant memories:\n$result")
    }
}
