package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/** 清除当前会话上下文。下一条消息起生效 (当前轮仍可用历史完成回复)。 */
class ClearContextTool : Tool() {
    override val name = "clear_context"
    override val description =
        "Clear the current conversation history/context. Takes effect from the next message."
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult =
        ToolResult(toAgent = "context cleared", userReply = "上下文已清除。")

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        ctx.store.clear(ctx.conversationId)
        return ToolResult(
            toAgent = "context cleared for conversation '${ctx.conversationId}'",
            userReply = "上下文已清除。",
        )
    }
}

/** 框架内置工具集合。Agent 在 [config.builtinTools] 打开时自动注册。 */
object BuiltinTools {
    fun all(): List<Tool> = listOf(
        ClearContextTool(),
    )
}
