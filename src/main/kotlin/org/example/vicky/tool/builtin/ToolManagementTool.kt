package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

class ToolManagementTool(
    private val onStateChange: (() -> Unit)? = null,
) : Tool() {
    override val name = "manage_tools"
    override val description =
        "List all tools and their status, or enable/disable a specific tool at runtime. " +
            "Use action='list' to see all tools. Use action='disable' or 'enable' with tool_name to toggle."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add(kotlinx.serialization.json.JsonPrimitive("list"))
                    add(kotlinx.serialization.json.JsonPrimitive("enable"))
                    add(kotlinx.serialization.json.JsonPrimitive("disable"))
                }
                put("description", "Operation: list all tools, enable a disabled tool, or disable an active tool.")
            }
            putJsonObject("tool_name") {
                put("type", "string")
                put("description", "Name of the tool to enable or disable. Required for enable/disable actions.")
            }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("action")) }
    }

    private val disabledTools = mutableMapOf<String, Tool>()

    fun getDisabledToolNames(): Set<String> = disabledTools.keys.toSet()

    override suspend fun execute(userId: String, args: JsonObject): ToolResult =
        ToolResult(toAgent = "error: manage_tools requires context to access tool registry")

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "error: missing required parameter 'action'")

        return when (action) {
            "list" -> listTools(ctx)
            "enable" -> toggleTool(ctx, args, enable = true)
            "disable" -> toggleTool(ctx, args, enable = false)
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be list, enable, or disable.")
        }
    }

    private fun listTools(ctx: ToolContext): ToolResult {
        val sb = StringBuilder()
        val activeTools = ctx.tools.snapshot().sortedBy { it.name }
        val disabledList = disabledTools.values.sortedBy { it.name }

        sb.appendLine("=== Active tools (${activeTools.size}) ===")
        for (tool in activeTools) {
            sb.appendLine("[active]   ${tool.name} — ${tool.description}")
        }

        if (disabledList.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== Disabled tools (${disabledList.size}) ===")
            for (tool in disabledList) {
                sb.appendLine("[disabled] ${tool.name} — ${tool.description}")
            }
        }

        return ToolResult(toAgent = sb.toString().trimEnd())
    }

    private fun toggleTool(ctx: ToolContext, args: JsonObject, enable: Boolean): ToolResult {
        val toolName = args["tool_name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'tool_name' for ${if (enable) "enable" else "disable"} action")

        return if (enable) {
            val tool = disabledTools.remove(toolName)
                ?: return ToolResult(
                    toAgent = if (ctx.tools[toolName] != null)
                        "error: tool '$toolName' is already active"
                    else
                        "error: tool '$toolName' not found in disabled tools"
                )
            ctx.tools.register(tool)
            onStateChange?.invoke()
            ToolResult(toAgent = "tool '$toolName' enabled successfully")
        } else {
            if (toolName == name) {
                return ToolResult(toAgent = "error: cannot disable '$name' (the tool management tool itself)")
            }
            val tool = ctx.tools.unregister(toolName)
                ?: return ToolResult(
                    toAgent = if (disabledTools.containsKey(toolName))
                        "error: tool '$toolName' is already disabled"
                    else
                        "error: tool '$toolName' not found"
                )
            disabledTools[toolName] = tool
            onStateChange?.invoke()
            ToolResult(toAgent = "tool '$toolName' disabled successfully")
        }
    }
}
