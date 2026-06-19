package org.example.vicky.channel.onebot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/**
 * 把当前群加入白名单并回写 config.json。
 *
 * 仅 admin 可调用（通过 [OneBot.adminToolList] 控制）。
 * 群 id 由 [ToolContext.groupId] 提供，私聊调用直接返回错误。
 */
class GroupWhitelistAddTool(
    private val groupWhitelist: MutableSet<String>,
    private val onChanged: () -> Unit,
) : Tool() {
    override val name = "group_whitelist_add"
    override val description =
        "Add the current group to the OneBot reply whitelist and persist to config.json. Admin-only."

    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult =
        ToolResult(toAgent = "Error: this tool requires conversation context (group id).")

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        val gid = ctx.groupId
        if (gid.isBlank()) {
            return ToolResult(toAgent = "Error: not in a group conversation.")
        }
        return if (groupWhitelist.add(gid)) {
            onChanged()
            ToolResult(toAgent = "added group $gid to whitelist", userReply = "已加入白名单：$gid")
        } else {
            ToolResult(toAgent = "group $gid already in whitelist", userReply = "已在白名单中")
        }
    }
}
