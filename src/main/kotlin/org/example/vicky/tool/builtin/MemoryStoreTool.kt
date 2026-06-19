package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.memory.Memory
import org.example.vicky.memory.MemoryStore
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/**
 * 手动存储蒸馏记忆。
 */
class MemoryStoreTool(
    private val memoryStore: MemoryStore,
) : Tool() {
    override val name = "memory_store"
    override val description =
        "Store a piece of information into long-term memory. " +
            "Use this to remember important facts, preferences, or decisions."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("content") {
                put("type", "string")
                put("description", "The content to remember.")
            }
            putJsonObject("tags") {
                put("type", "string")
                put("description", "Optional comma-separated tags for categorization.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("content")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        return executeInternal(userId, args)
    }

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        return executeInternal(ctx.userId, args)
    }

    private suspend fun executeInternal(userId: String, args: JsonObject): ToolResult {
        val content = args["content"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'content'.")
        if (content.isBlank()) {
            return ToolResult(toAgent = "Error: content cannot be empty.")
        }

        val tagsStr = args["tags"]?.jsonPrimitive?.content?.trim()
        val tags = if (tagsStr.isNullOrBlank()) emptySet() else tagsStr.split(",").map { it.trim() }.toSet()

        val memory = Memory(
            content = content,
            summary = content,
            tags = tags,
            userId = userId,
            source = "user_stated",
            confidence = 1.0f,
        )

        memoryStore.remember(memory)
        return ToolResult(
            toAgent = "Memory stored successfully.",
            userReply = "已记住。",
        )
    }
}
