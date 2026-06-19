package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.file.FileIndexService
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult

/**
 * 语义搜索已索引的文件。
 */
class FileSearchTool(
    private val fileIndexService: FileIndexService?,
) : Tool() {
    override val name = "file_search"
    override val description =
        "Semantic search indexed files by content."

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
        if (fileIndexService == null) {
            return ToolResult(toAgent = "Error: file indexing is not enabled.")
        }

        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'query'.")
        val topK = args["top_k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        val results = fileIndexService.search(query, topK)
        if (results.isEmpty()) {
            return ToolResult(toAgent = "No matching files found.")
        }

        val result = results.joinToString("\n\n") { searchResult ->
            "File: ${searchResult.path} (lines ${searchResult.startLine}-${searchResult.endLine}, score: %.2f)\n${searchResult.chunk}".format(
                searchResult.score
            )
        }
        return ToolResult(toAgent = "Found ${results.size} matching results:\n$result")
    }
}
