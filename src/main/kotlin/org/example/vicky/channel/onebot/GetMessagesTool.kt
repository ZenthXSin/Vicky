package org.example.vicky.channel.onebot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/**
 * 查询消息缓冲区的统一工具。
 *
 * 参数：
 * - mode: "all" (全部缓冲消息) | "unread" (上次查询后的新消息)
 * - type: "text" (纯文本) | "media" (富媒体) | "raw" (原始消息链)
 */
class GetMessagesTool : Tool() {
    override val name = "get_messages"
    override val description =
        "Query the message buffer for this conversation. " +
            "mode: 'all' returns all buffered messages, 'unread' returns only new messages since last query. " +
            "type: 'text' = plain text, 'media' = images/audio/video, 'raw' = original message chain."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("mode") {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("all"))
                    add(kotlinx.serialization.json.JsonPrimitive("unread"))
                })
                put("description", "'all' = all buffered messages, 'unread' = only new since last query.")
            }
            putJsonObject("type") {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("text"))
                    add(kotlinx.serialization.json.JsonPrimitive("media"))
                    add(kotlinx.serialization.json.JsonPrimitive("raw"))
                })
                put("description", "'text' = plain text, 'media' = rich media (image/audio/video), 'raw' = original message.")
            }
        }
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("mode"))
            add(kotlinx.serialization.json.JsonPrimitive("type"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult =
        ToolResult(toAgent = "Error: this tool requires ToolContext with a MessageBuffer.")

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        val buf = ctx.buffer
            ?: return ToolResult(toAgent = "Error: message buffer is not available in this context.")

        val mode = args["mode"]?.jsonPrimitive?.content ?: "all"
        val type = args["type"]?.jsonPrimitive?.content ?: "text"
        val unread = mode == "unread"
        val convId = ctx.conversationId

        val result = when (type) {
            "text" -> formatText(buf.getText(convId, unread))
            "media" -> formatMedia(buf.getRichMedia(convId, unread))
            "raw" -> formatRaw(buf.getRaw(convId, unread))
            else -> "Error: unknown type '$type'. Use 'text', 'media', or 'raw'."
        }

        // unread 查询后自动推进游标
        if (unread) buf.markRead(convId)

        return ToolResult(toAgent = result)
    }

    // region 格式化

    private fun formatText(messages: List<BufferedMessage>): String {
        if (messages.isEmpty()) return "(no text messages in buffer)"
        return messages.joinToString("\n") { msg ->
            "[${msg.senderName}(${msg.userId})] ${msg.text}"
        }
    }

    private fun formatMedia(messages: List<BufferedMessage>): String {
        val allMedia = messages.flatMap { msg ->
            msg.richMedia.map { media ->
                "[${msg.senderName}(${msg.userId})] ${media.type}: ${media.description}" +
                    if (media.url.isNotEmpty()) " (url: ${media.url})" else ""
            }
        }
        if (allMedia.isEmpty()) return "(no rich media in buffer)"
        return allMedia.joinToString("\n")
    }

    private fun formatRaw(messages: List<BufferedMessage>): String {
        if (messages.isEmpty()) return "(no messages in buffer)"
        return messages.joinToString("\n---\n") { msg ->
            "[${msg.senderName}(${msg.userId})]\n${msg.raw}"
        }
    }

    // endregion
}
