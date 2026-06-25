package org.example.vicky.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult

/**
 * 将 MCP 服务器暴露的工具桥接为 Vicky [Tool]。
 *
 * Agent 调用时委托给 MCP 客户端的 `callTool`，结果转为 [ToolResult]。
 */
class McpTool(
    private val client: Client,
    private val mcpTool: io.modelcontextprotocol.kotlin.sdk.types.Tool,
    private val serverName: String,
) : Tool() {
    override val name: String = mcpTool.name
    override val description: String = buildString {
        append(mcpTool.description ?: "")
        if (serverName.isNotEmpty()) {
            append(" [MCP: $serverName]")
        }
    }
    override val parameters: JsonObject = toolSchemaToJsonObject(mcpTool.inputSchema)

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val result = client.callTool(name, args)
        val text = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
        return ToolResult(
            toAgent = text.ifEmpty { "(no output)" },
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 将 MCP 的 [ToolSchema] 转为 Vicky 需要的 JSON Schema [JsonObject]。 */
        fun toolSchemaToJsonObject(schema: ToolSchema): JsonObject {
            val base = json.encodeToJsonElement(schema).jsonObject.toMutableMap()
            base["type"] = kotlinx.serialization.json.JsonPrimitive("object")
            return JsonObject(base)
        }
    }
}
