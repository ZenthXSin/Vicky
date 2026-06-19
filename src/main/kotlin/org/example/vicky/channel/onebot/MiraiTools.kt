package org.example.vicky.channel.onebot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.NormalMember
import net.mamoe.mirai.contact.announcement.AnnouncementParametersBuilder
import net.mamoe.mirai.contact.announcement.OfflineAnnouncement
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.contact.roaming.RoamingMessages
import net.mamoe.mirai.data.UserProfile
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.plus
import net.mamoe.mirai.message.data.source
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import org.example.vicky.tool.ToolResult
import java.util.concurrent.ConcurrentHashMap

// ─── bot_info ────────────────────────────────────────────────

class BotInfoTool(bot: Bot) : MiraiTool(bot) {
    override val name = "bot_info"
    override val description = "Get the bot's own info: QQ id, nickname, online status, friend/group count."
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult = ToolResult(
        toAgent = buildString {
            appendLine("Bot ID: ${bot.id}")
            appendLine("Nick: ${bot.nick}")
            appendLine("Online: ${bot.isOnline}")
            appendLine("Friends: ${bot.friends.size}")
            appendLine("Groups: ${bot.groups.size}")
        }
    )
}

// ─── contacts ────────────────────────────────────────────────

class ContactsTool(bot: Bot) : MiraiTool(bot) {
    override val name = "contacts"
    override val description =
        "List bot's contacts. Pass type='friends' to list friends, type='groups' to list groups, " +
            "type='strangers' to list strangers."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("type") {
                put("type", "string")
                put("description", "One of: 'friends', 'groups', 'strangers'. Default 'friends'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("friends")); add(JsonPrimitive("groups")); add(JsonPrimitive("strangers"))
                })
            }
        }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val type = args["type"]?.jsonPrimitive?.content?.trim() ?: "friends"
        return when (type) {
            "friends" -> {
                val list = bot.friends
                if (list.isEmpty()) ToolResult(toAgent = "No friends.")
                else ToolResult(toAgent = list.joinToString("\n") { "${it.id} | ${it.nick} | remark=${it.remark}" })
            }
            "groups" -> {
                val list = bot.groups
                if (list.isEmpty()) ToolResult(toAgent = "No groups.")
                else ToolResult(toAgent = list.joinToString("\n") {
                    "${it.id} | ${it.name} | members=${it.members.size} | botPerm=${permLabel(it.botPermission)}"
                })
            }
            "strangers" -> {
                val list = bot.strangers
                if (list.isEmpty()) ToolResult(toAgent = "No strangers.")
                else ToolResult(toAgent = list.joinToString("\n") { "${it.id} | ${it.nick}" })
            }
            else -> ToolResult(toAgent = "Error: unknown type '$type'. Use 'friends', 'groups' or 'strangers'.")
        }
    }
}

// ─── group_info ──────────────────────────────────────────────

class GroupInfoTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_info"
    override val description = "Get detailed info of a group by its QQ group id."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val group = bot.getGroup(gid)
            ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val s = group.settings
        return ToolResult(
            toAgent = buildString {
                appendLine("Group ID: ${group.id}")
                appendLine("Name: ${group.name}")
                appendLine("Owner: ${group.owner.id} (${group.owner.nameCardOrNick})")
                appendLine("Members: ${group.members.size}")
                appendLine("Bot Permission: ${permLabel(group.botPermission)}")
                appendLine("Bot Muted: ${group.isBotMuted} (remaining ${group.botMuteRemaining}s)")
                appendLine("Mute All: ${s.isMuteAll}")
                appendLine("Allow Member Invite: ${s.isAllowMemberInvite}")
                appendLine("Anonymous Chat: ${s.isAnonymousChatEnabled}")
            }
        )
    }
}

// ─── group_members ───────────────────────────────────────────

class GroupMembersTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_members"
    override val description = "List members of a group with their id, nameCard, permission and mute status."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val group = bot.getGroup(gid)
            ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val members = group.members
        if (members.isEmpty()) return ToolResult(toAgent = "Group $gid has no members (except bot).")
        val lines = members.map { m ->
            val muted = if (m.isMuted) "muted(${m.muteTimeRemaining}s)" else "ok"
            "${m.id} | ${m.nameCardOrNick} | ${permLabel(m.permission)} | $muted"
        }
        return ToolResult(
            toAgent = "Group ${group.id} (${group.name}) — ${lines.size} members:\n" +
                lines.joinToString("\n")
        )
    }
}

// ─── user_profile ────────────────────────────────────────────

class UserProfileTool(bot: Bot) : MiraiTool(bot) {
    override val name = "user_profile"
    override val description =
        "Query a QQ user's profile: nickname, age, level, sex, sign. " +
            "The target user must be a friend or a group member the bot can see."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "The QQ number of the target user.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("targetId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        // Find the user as friend or group member
        val user = bot.getFriend(tid)
            ?: bot.groups.firstNotNullOfOrNull { g -> g[tid] }
            ?: return ToolResult(toAgent = "Error: user $tid not found as friend or group member.")
        val profile: UserProfile = user.queryProfile()
        return ToolResult(
            toAgent = buildString {
                appendLine("QQ: ${user.id}")
                appendLine("Nickname: ${profile.nickname}")
                appendLine("Age: ${profile.age}")
                appendLine("QQ Level: ${profile.qLevel}")
                appendLine("Sex: ${profile.sex}")
                appendLine("Sign: ${profile.sign}")
                appendLine("Email: ${profile.email}")
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Level 2 — 主动操作
// ═══════════════════════════════════════════════════════════

// ─── send_message ────────────────────────────────────────────

class SendMessageTool(bot: Bot) : MiraiTool(bot) {
    override val name = "send_message"
    override val description =
        "Proactively send a text message to a group, friend, or temp-session stranger. " +
            "Specify chatType='group'|'friend'|'temp', targetId (group id or QQ), and content."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("chatType") {
                put("type", "string")
                put("description", "'group', 'friend', or 'temp' (temp session / stranger).")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("group")); add(JsonPrimitive("friend")); add(JsonPrimitive("temp"))
                })
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "Group id (for group) or QQ number (for friend/temp).")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "The text message to send (max 4500 chars).")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("chatType"))
            add(JsonPrimitive("targetId"))
            add(JsonPrimitive("content"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val chatType = args["chatType"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'chatType'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val content = args["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(toAgent = "Error: missing or empty 'content'.")

        return when (chatType) {
            "group" -> {
                val group = bot.getGroup(tid) ?: return ToolResult(toAgent = "Error: group $tid not found.")
                group.sendMessage(content)
                ToolResult(toAgent = "Message sent to group $tid.", userReply = null)
            }
            "friend" -> {
                val friend = bot.getFriend(tid) ?: return ToolResult(toAgent = "Error: friend $tid not found.")
                friend.sendMessage(content)
                ToolResult(toAgent = "Message sent to friend $tid.", userReply = null)
            }
            "temp" -> {
                // 临时会话/陌生人: 优先 stranger，再试 friend
                val contact = bot.getStranger(tid) ?: bot.getFriend(tid)
                    ?: return ToolResult(toAgent = "Error: user $tid not found as stranger or friend.")
                contact.sendMessage(content)
                ToolResult(toAgent = "Message sent to temp-session user $tid.", userReply = null)
            }
            else -> ToolResult(toAgent = "Error: chatType must be 'group', 'friend', or 'temp'.")
        }
    }
}

// ─── group_manage ────────────────────────────────────────────

class GroupManageTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_manage"
    override val description =
        "Manage group: mute/kick/admin/rename/mute-all/title."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: mute, unmute, kick, set_admin, rename, mute_all, title.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("mute")); add(JsonPrimitive("unmute")); add(JsonPrimitive("kick"))
                    add(JsonPrimitive("set_admin")); add(JsonPrimitive("rename"))
                    add(JsonPrimitive("mute_all")); add(JsonPrimitive("title"))
                })
            }
            putJsonObject("memberId") {
                put("type", "string")
                put("description", "Target member QQ (required for mute/unmute/kick/set_admin/title).")
            }
            putJsonObject("durationSeconds") {
                put("type", "integer")
                put("description", "Mute duration in seconds (for 'mute', default 600).")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Kick reason message (for 'kick').")
            }
            putJsonObject("block") {
                put("type", "boolean")
                put("description", "Whether to blacklist after kick (for 'kick', default false).")
            }
            putJsonObject("value") {
                put("type", "string")
                put("description", "New group name (for 'rename'), admin grant (true/false for 'set_admin'), " +
                    "mute-all toggle (true/false for 'mute_all'), or special title text (for 'title').")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")); add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")

        fun resolveMember(): NormalMember? {
            val mid = args["memberId"]?.jsonPrimitive?.long ?: return null
            return group[mid]
        }

        return when (action) {
            "mute" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                val dur = args["durationSeconds"]?.jsonPrimitive?.int ?: 600
                m.mute(dur)
                ToolResult(toAgent = "Muted ${m.id} for ${dur}s.")
            }
            "unmute" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                m.unmute()
                ToolResult(toAgent = "Unmuted ${m.id}.")
            }
            "kick" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                val msg = args["message"]?.jsonPrimitive?.content ?: ""
                val block = args["block"]?.jsonPrimitive?.boolean ?: false
                m.kick(msg, block)
                ToolResult(toAgent = "Kicked ${m.id} (block=$block, msg='$msg').")
            }
            "set_admin" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                val grant = args["value"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: return ToolResult(toAgent = "Error: 'value' must be 'true' or 'false' for set_admin.")
                m.modifyAdmin(grant)
                ToolResult(toAgent = "Set admin=${grant} for ${m.id}.")
            }
            "rename" -> {
                val newName = args["value"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(toAgent = "Error: missing 'value' (new group name).")
                group.name = newName
                ToolResult(toAgent = "Group $gid renamed to '$newName'.")
            }
            "mute_all" -> {
                val enable = args["value"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: return ToolResult(toAgent = "Error: 'value' must be 'true' or 'false' for mute_all.")
                group.settings.isMuteAll = enable
                ToolResult(toAgent = "Mute-all set to $enable for group $gid.")
            }
            "title" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                val title = args["value"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'value' (special title text).")
                m.specialTitle = title
                ToolResult(toAgent = "Set special title '$title' for ${m.id}.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }
}

// ─── friend_manage ───────────────────────────────────────────

class FriendManageTool(bot: Bot) : MiraiTool(bot) {
    override val name = "friend_manage"
    override val description =
        "Manage a friend. Actions: 'delete' (remove and block friend), 'remark' (change remark/note name)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("friendId") {
                put("type", "string")
                put("description", "Friend's QQ number.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "'delete' or 'remark'.")
                put("enum", buildJsonArray { add(JsonPrimitive("delete")); add(JsonPrimitive("remark")) })
            }
            putJsonObject("remark") {
                put("type", "string")
                put("description", "New remark text (required for action='remark').")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("friendId")); add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val fid = args["friendId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'friendId'.")
        val friend = bot.getFriend(fid) ?: return ToolResult(toAgent = "Error: friend $fid not found.")
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")

        return when (action) {
            "delete" -> {
                friend.delete()
                ToolResult(toAgent = "Deleted and blocked friend $fid.")
            }
            "remark" -> {
                val newRemark = args["remark"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'remark' text.")
                friend.remark = newRemark
                ToolResult(toAgent = "Set remark '$newRemark' for friend $fid.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'. Use 'delete' or 'remark'.")
        }
    }
}

// ─── group_quit ──────────────────────────────────────────────

class GroupQuitTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_quit"
    override val description = "Quit (leave) a group. Bot must NOT be the owner."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id to quit.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")
        return try {
            val ok = group.quit()
            if (ok) ToolResult(toAgent = "Quit group $gid successfully.")
            else ToolResult(toAgent = "Already left group $gid or quit failed.")
        } catch (e: IllegalStateException) {
            ToolResult(toAgent = "Error: cannot quit group $gid — ${e.message} (bot may be the owner).")
        }
    }
}

// ─── group_announcements ─────────────────────────────────────

class GroupAnnouncementsTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_announcements"
    override val description =
        "Manage group announcements: list/publish/delete."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "'list', 'publish', or 'delete'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list")); add(JsonPrimitive("publish")); add(JsonPrimitive("delete"))
                })
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Announcement text content (required for 'publish').")
            }
            putJsonObject("fid") {
                put("type", "string")
                put("description", "Announcement fid (required for 'delete').")
            }
            putJsonObject("pinned") {
                put("type", "boolean")
                put("description", "Pin the announcement (for 'publish', default false).")
            }
            putJsonObject("requireConfirmation") {
                put("type", "boolean")
                put("description", "Require members to confirm (for 'publish', default false).")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")); add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")
        val announcements = group.announcements

        return when (action) {
            "list" -> {
                val list = announcements.toList()
                if (list.isEmpty()) return ToolResult(toAgent = "Group $gid has no announcements.")
                val lines = list.map { a ->
                    val sender = a.sender?.nameCardOrNick ?: a.senderId.toString()
                    "fid=${a.fid} | by=$sender | confirmed=${a.confirmedMembersCount} | pinned=${a.parameters.isPinned}\n  ${a.content.take(120)}"
                }
                ToolResult(toAgent = "Group $gid announcements (${lines.size}):\n${lines.joinToString("\n")}")
            }
            "publish" -> {
                val content = args["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(toAgent = "Error: missing or empty 'content'.")
                val pinned = args["pinned"]?.jsonPrimitive?.boolean ?: false
                val confirm = args["requireConfirmation"]?.jsonPrimitive?.boolean ?: false
                val params = AnnouncementParametersBuilder().apply {
                    isPinned = pinned; requireConfirmation = confirm
                }.build()
                val online = group.announcements.publish(OfflineAnnouncement(content, params))
                ToolResult(toAgent = "Published announcement fid=${online.fid} to group $gid.")
            }
            "delete" -> {
                val fid = args["fid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(toAgent = "Error: missing 'fid'.")
                val ok = announcements.delete(fid)
                if (ok) ToolResult(toAgent = "Deleted announcement $fid from group $gid.")
                else ToolResult(toAgent = "Announcement $fid not found in group $gid.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }

}

// ─── at_member ───────────────────────────────────────────────

class AtTool(bot: Bot) : MiraiTool(bot) {
    override val name = "at_member"
    override val description =
        "Send a message that @mentions a user in a group. " +
            "The bot will send '@targetId text' to the group. " +
            "Use this when you need to ping or call someone's attention."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "QQ number of the user to @mention.")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Optional text to send after the @mention.")
            }
            putJsonObject("endTurn") {
                put("type", "boolean")
                put("description", "If true, end the bot's turn after sending the @mention. No further text will be generated. Default true.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")); add(JsonPrimitive("targetId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val member = group[tid] ?: return ToolResult(toAgent = "Error: member $tid not found in group $gid.")

        val text = args["text"]?.jsonPrimitive?.content ?: ""
        val msg = At(tid) + if (text.isNotBlank()) " $text" else ""
        group.sendMessage(msg)
        val endTurn = args["endTurn"]?.jsonPrimitive?.boolean ?: true
        return ToolResult(toAgent = "Sent @${member.nameCardOrNick}($tid) in group $gid.", userReply = null, endTurn = endTurn)
    }
}

// ─── reply_message ───────────────────────────────────────────

class ReplyMessageTool(
    bot: Bot,
    private val sourceCache: ConcurrentHashMap<String, MessageSource>,
) : MiraiTool(bot) {
    override val name = "reply_message"
    override val description =
        "Reply (quote) to a specific message. Pass the messageRef (e.g. '#42') seen in chat history, " +
            "target chat (groupId or friendId), and reply text. " +
            "The bot will send a quoted reply message."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("messageRef") {
                put("type", "string")
                put("description", "Message reference id from chat history, e.g. '#42'.")
            }
            putJsonObject("chatType") {
                put("type", "string")
                put("description", "'group' or 'friend'. Default 'group'.")
                put("enum", buildJsonArray { add(JsonPrimitive("group")); add(JsonPrimitive("friend")) })
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "Group id or friend QQ to send the reply to.")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "The reply text content.")
            }
            putJsonObject("endTurn") {
                put("type", "boolean")
                put("description", "If true, end the bot's turn after sending the reply. No further text will be generated. Default true.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("messageRef"))
            add(JsonPrimitive("targetId"))
            add(JsonPrimitive("text"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val ref = args["messageRef"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'messageRef'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val text = args["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(toAgent = "Error: missing or empty 'text'.")
        val chatType = args["chatType"]?.jsonPrimitive?.content?.trim() ?: "group"

        val source = sourceCache[ref]
            ?: return ToolResult(toAgent = "Error: messageRef '$ref' not found in cache. It may have expired.")

        val msg = QuoteReply(source) + text
        val endTurn = args["endTurn"]?.jsonPrimitive?.boolean ?: true

        return when (chatType) {
            "group" -> {
                val group = bot.getGroup(tid) ?: return ToolResult(toAgent = "Error: group $tid not found.")
                group.sendMessage(msg)
                ToolResult(toAgent = "Replied to $ref in group $tid.", userReply = null, endTurn = endTurn)
            }
            "friend" -> {
                val friend = bot.getFriend(tid) ?: return ToolResult(toAgent = "Error: friend $tid not found.")
                friend.sendMessage(msg)
                ToolResult(toAgent = "Replied to $ref in friend chat $tid.", userReply = null, endTurn = endTurn)
            }
            else -> ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }
    }
}

// ─── recall_message ─────────────────────────────────────────

class RecallMessageTool(
    bot: Bot,
    private val sourceCache: ConcurrentHashMap<String, MessageSource>,
) : MiraiTool(bot) {
    override val name = "recall_message"
    override val description =
        "Recall (retract) a sent message. Pass the messageRef (e.g. '#42') seen in chat history. " +
            "Bot can recall its own messages; admin can recall group member messages."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("messageRef") {
                put("type", "string")
                put("description", "Message reference id from chat history, e.g. '#42'.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("messageRef")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val ref = args["messageRef"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'messageRef'.")
        val source = sourceCache[ref]
            ?: return ToolResult(toAgent = "Error: messageRef '$ref' not found in cache. It may have expired.")
        return try {
            Mirai.recallMessage(bot, source)
            ToolResult(toAgent = "Recalled message $ref.")
        } catch (e: Exception) {
            ToolResult(toAgent = "Error recalling message $ref: ${e.message}")
        }
    }
}

// ─── send_image ────────────────────────────────────────────

class SendImageTool(bot: Bot) : MiraiTool(bot) {
    override val name = "send_image"
    override val description =
        "Send an image to a group, friend, or temp-session user. " +
            "Provide chatType, targetId, and imageUrl (a direct URL to an image file)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("chatType") {
                put("type", "string")
                put("description", "'group', 'friend', or 'temp'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("group")); add(JsonPrimitive("friend")); add(JsonPrimitive("temp"))
                })
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "Group id or QQ number.")
            }
            putJsonObject("imageUrl") {
                put("type", "string")
                put("description", "Direct URL to the image file.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("chatType")); add(JsonPrimitive("targetId")); add(JsonPrimitive("imageUrl"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val chatType = args["chatType"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'chatType'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val imageUrl = args["imageUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(toAgent = "Error: missing or empty 'imageUrl'.")

        val contact = when (chatType) {
            "group" -> bot.getGroup(tid) ?: return ToolResult(toAgent = "Error: group $tid not found.")
            "friend" -> bot.getFriend(tid) ?: return ToolResult(toAgent = "Error: friend $tid not found.")
            "temp" -> bot.getStranger(tid) ?: bot.getFriend(tid)
                ?: return ToolResult(toAgent = "Error: user $tid not found.")
            else -> return ToolResult(toAgent = "Error: chatType must be 'group', 'friend', or 'temp'.")
        }

        return try {
            val image = java.net.URL(imageUrl).openStream().use { stream ->
                stream.toExternalResource().use { resource ->
                    contact.uploadImage(resource)
                }
            }
            contact.sendMessage(image)
            ToolResult(toAgent = "Image sent to $chatType $tid.", userReply = null)
        } catch (e: Exception) {
            ToolResult(toAgent = "Error sending image: ${e.message}")
        }
    }
}

// ─── send_video ────────────────────────────────────────────

class SendVideoTool(bot: Bot) : MiraiTool(bot) {
    override val name = "send_video"
    override val description =
        "Send a short video to a group or friend. " +
            "Provide chatType, targetId, videoUrl, and optionally thumbnailUrl."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("chatType") {
                put("type", "string")
                put("description", "'group' or 'friend'.")
                put("enum", buildJsonArray { add(JsonPrimitive("group")); add(JsonPrimitive("friend")) })
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "Group id or QQ number.")
            }
            putJsonObject("videoUrl") {
                put("type", "string")
                put("description", "Direct URL to the video file (mp4).")
            }
            putJsonObject("thumbnailUrl") {
                put("type", "string")
                put("description", "Direct URL to the thumbnail image. Optional, auto-generated if omitted.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("chatType")); add(JsonPrimitive("targetId")); add(JsonPrimitive("videoUrl"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val chatType = args["chatType"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'chatType'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val videoUrl = args["videoUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(toAgent = "Error: missing or empty 'videoUrl'.")

        val contact = when (chatType) {
            "group" -> bot.getGroup(tid) ?: return ToolResult(toAgent = "Error: group $tid not found.")
            "friend" -> bot.getFriend(tid) ?: return ToolResult(toAgent = "Error: friend $tid not found.")
            else -> return ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }

        return try {
            val thumbnailUrl = args["thumbnailUrl"]?.jsonPrimitive?.content
            val videoResource = java.net.URL(videoUrl).openStream().use { stream ->
                stream.toExternalResource()
            }
            val thumbnailResource = if (!thumbnailUrl.isNullOrBlank()) {
                java.net.URL(thumbnailUrl).openStream().use { stream ->
                    stream.toExternalResource()
                }
            } else {
                java.net.URL(videoUrl).openStream().use { stream ->
                    stream.toExternalResource()
                }
            }
            val shortVideo = contact.uploadShortVideo(thumbnailResource, videoResource)
            contact.sendMessage(shortVideo)
            ToolResult(toAgent = "Video sent to $chatType $tid.", userReply = null)
        } catch (e: Exception) {
            ToolResult(toAgent = "Error sending video: ${e.message}")
        }
    }
}

// ─── friend_request ────────────────────────────────────────

class FriendRequestTool(bot: Bot) : MiraiTool(bot) {
    override val name = "friend_request"
    override val description =
        "Manage friend requests. Actions: 'list' (list pending requests), " +
            "'accept' (accept a request by eventId), 'reject' (reject a request by eventId, optional blacklist)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "'list', 'accept', or 'reject'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list")); add(JsonPrimitive("accept")); add(JsonPrimitive("reject"))
                })
            }
            putJsonObject("eventId") {
                put("type", "string")
                put("description", "Event id (required for accept/reject).")
            }
            putJsonObject("blacklist") {
                put("type", "boolean")
                put("description", "Whether to blacklist after reject (default false).")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")

        return when (action) {
            "list" -> {
                val events = mutableListOf<net.mamoe.mirai.event.events.NewFriendRequestEvent>()
                bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.NewFriendRequestEvent> {
                    events.add(this)
                }
                if (events.isEmpty()) {
                    ToolResult(toAgent = "No pending friend requests.")
                } else {
                    val lines = events.map { e ->
                        "eventId=${e.eventId} | from=${e.fromId}(${e.fromNick}) | msg='${e.message}' | group=${e.fromGroupId}"
                    }
                    ToolResult(toAgent = "Pending friend requests (${events.size}):\n${lines.joinToString("\n")}")
                }
            }
            "accept" -> {
                val eventId = args["eventId"]?.jsonPrimitive?.long
                    ?: return ToolResult(toAgent = "Error: missing 'eventId'.")
                // Use Mirai API to accept
                try {
                    val event = findFriendRequestEvent(eventId)
                        ?: return ToolResult(toAgent = "Error: friend request event $eventId not found (may have expired).")
                    event.accept()
                    ToolResult(toAgent = "Accepted friend request from ${event.fromId}(${event.fromNick}).")
                } catch (e: Exception) {
                    ToolResult(toAgent = "Error accepting friend request: ${e.message}")
                }
            }
            "reject" -> {
                val eventId = args["eventId"]?.jsonPrimitive?.long
                    ?: return ToolResult(toAgent = "Error: missing 'eventId'.")
                val blacklist = args["blacklist"]?.jsonPrimitive?.boolean ?: false
                try {
                    val event = findFriendRequestEvent(eventId)
                        ?: return ToolResult(toAgent = "Error: friend request event $eventId not found (may have expired).")
                    event.reject(blacklist)
                    ToolResult(toAgent = "Rejected friend request from ${event.fromId}(${event.fromNick}), blacklist=$blacklist.")
                } catch (e: Exception) {
                    ToolResult(toAgent = "Error rejecting friend request: ${e.message}")
                }
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'. Use 'list', 'accept', or 'reject'.")
        }
    }

    private suspend fun findFriendRequestEvent(eventId: Long): net.mamoe.mirai.event.events.NewFriendRequestEvent? {
        var found: net.mamoe.mirai.event.events.NewFriendRequestEvent? = null
        bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.NewFriendRequestEvent> {
            if (this.eventId == eventId) found = this
        }
        return found
    }
}

// ─── group_invite ──────────────────────────────────────────

class GroupInviteTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_invite"
    override val description =
        "Manage group invitations (bot invited to join group). " +
            "Actions: 'accept' (accept invitation by eventId), 'ignore' (ignore invitation by eventId)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "'accept' or 'ignore'.")
                put("enum", buildJsonArray { add(JsonPrimitive("accept")); add(JsonPrimitive("ignore")) })
            }
            putJsonObject("eventId") {
                put("type", "string")
                put("description", "Event id of the invitation.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")); add(JsonPrimitive("eventId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")
        val eventId = args["eventId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'eventId'.")

        return try {
            val event = findGroupInviteEvent(eventId)
                ?: return ToolResult(toAgent = "Error: group invite event $eventId not found (may have expired).")
            when (action) {
                "accept" -> {
                    event.accept()
                    ToolResult(toAgent = "Accepted group invite from ${event.invitorId}(${event.invitorNick}) to group ${event.groupId}(${event.groupName}).")
                }
                "ignore" -> {
                    event.ignore()
                    ToolResult(toAgent = "Ignored group invite from ${event.invitorId}(${event.invitorNick}) to group ${event.groupId}(${event.groupName}).")
                }
                else -> ToolResult(toAgent = "Error: unknown action '$action'. Use 'accept' or 'ignore'.")
            }
        } catch (e: Exception) {
            ToolResult(toAgent = "Error handling group invite: ${e.message}")
        }
    }

    private suspend fun findGroupInviteEvent(eventId: Long): net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent? {
        var found: net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent? = null
        bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent> {
            if (this.eventId == eventId) found = this
        }
        return found
    }
}

// ─── member_join_request ───────────────────────────────────

class MemberJoinRequestTool(bot: Bot) : MiraiTool(bot) {
    override val name = "member_join_request"
    override val description =
        "Manage group join requests (someone requests to join a group where bot is admin). " +
            "Actions: 'accept' (accept by eventId), 'reject' (reject by eventId, optional blacklist and message), " +
            "'ignore' (ignore by eventId)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "'accept', 'reject', or 'ignore'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("accept")); add(JsonPrimitive("reject")); add(JsonPrimitive("ignore"))
                })
            }
            putJsonObject("eventId") {
                put("type", "string")
                put("description", "Event id of the join request.")
            }
            putJsonObject("blacklist") {
                put("type", "boolean")
                put("description", "Whether to blacklist after reject (default false).")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Rejection message (for reject action).")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")); add(JsonPrimitive("eventId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")
        val eventId = args["eventId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'eventId'.")

        return try {
            val event = findMemberJoinRequestEvent(eventId)
                ?: return ToolResult(toAgent = "Error: member join request event $eventId not found (may have expired).")
            when (action) {
                "accept" -> {
                    event.accept()
                    ToolResult(toAgent = "Accepted join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}(${event.groupName}).")
                }
                "reject" -> {
                    val blacklist = args["blacklist"]?.jsonPrimitive?.boolean ?: false
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    event.reject(blacklist, message)
                    ToolResult(toAgent = "Rejected join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}, blacklist=$blacklist.")
                }
                "ignore" -> {
                    val blacklist = args["blacklist"]?.jsonPrimitive?.boolean ?: false
                    event.ignore(blacklist)
                    ToolResult(toAgent = "Ignored join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}.")
                }
                else -> ToolResult(toAgent = "Error: unknown action '$action'.")
            }
        } catch (e: Exception) {
            ToolResult(toAgent = "Error handling member join request: ${e.message}")
        }
    }

    private suspend fun findMemberJoinRequestEvent(eventId: Long): net.mamoe.mirai.event.events.MemberJoinRequestEvent? {
        var found: net.mamoe.mirai.event.events.MemberJoinRequestEvent? = null
        bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.MemberJoinRequestEvent> {
            if (this.eventId == eventId) found = this
        }
        return found
    }
}

// ─── set_name_card ─────────────────────────────────────────

class SetNameCardTool(bot: Bot) : MiraiTool(bot) {
    override val name = "set_name_card"
    override val description =
        "Set a member's group name card (群名片). Requires admin or owner permission. " +
            "Pass empty string to reset to default."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("memberId") {
                put("type", "string")
                put("description", "Target member QQ number.")
            }
            putJsonObject("nameCard") {
                put("type", "string")
                put("description", "New name card text (empty string to reset).")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("groupId")); add(JsonPrimitive("memberId")); add(JsonPrimitive("nameCard"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val mid = args["memberId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'memberId'.")
        val nameCard = args["nameCard"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'nameCard'.")

        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")
        val member = group[mid] ?: return ToolResult(toAgent = "Error: member $mid not found in group $gid.")

        return try {
            member.nameCard = nameCard
            val display = if (nameCard.isEmpty()) "(reset to default)" else "'$nameCard'"
            ToolResult(toAgent = "Set name card for ${member.id}(${member.nameCardOrNick}) in group $gid to $display.")
        } catch (e: Exception) {
            ToolResult(toAgent = "Error setting name card: ${e.message}")
        }
    }
}

// ─── essence_message ───────────────────────────────────────

class EssenceMessageTool(
    bot: Bot,
    private val sourceCache: ConcurrentHashMap<String, MessageSource>,
) : MiraiTool(bot) {
    override val name = "essence_message"
    override val description =
        "Manage group essence messages. Actions: " +
            "'list' (list essence messages), 'add' (set a message as essence by messageRef), " +
            "'remove' (remove from essence by messageRef)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "'list', 'add', or 'remove'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list")); add(JsonPrimitive("add")); add(JsonPrimitive("remove"))
                })
            }
            putJsonObject("messageRef") {
                put("type", "string")
                put("description", "Message reference id from chat history, e.g. '#42' (for add/remove).")
            }
            putJsonObject("start") {
                put("type", "integer")
                put("description", "Start index for pagination (for list, default 0).")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Page size (for list, default 20, max 50).")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")); add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")

        return when (action) {
            "list" -> {
                val start = args["start"]?.jsonPrimitive?.int ?: 0
                val limit = (args["limit"]?.jsonPrimitive?.int ?: 20).coerceAtMost(50)
                val records = group.essences.getPage(start, limit)
                if (records.isEmpty()) {
                    ToolResult(toAgent = "Group $gid has no essence messages (or page exhausted).")
                } else {
                    val lines = records.map { r ->
                        val sender = r.sender?.nameCardOrNick ?: r.senderId.toString()
                        "sender=$sender(${r.senderId}) | time=${r.senderTime} | operator=${r.operatorId}"
                    }
                    ToolResult(toAgent = "Group $gid essence messages (start=$start, ${lines.size} results):\n${lines.joinToString("\n")}")
                }
            }
            "add" -> {
                val ref = args["messageRef"]?.jsonPrimitive?.content?.trim()
                    ?: return ToolResult(toAgent = "Error: missing 'messageRef'.")
                val source = sourceCache[ref]
                    ?: return ToolResult(toAgent = "Error: messageRef '$ref' not found in cache.")
                val ok = group.setEssenceMessage(source)
                if (ok) ToolResult(toAgent = "Set message $ref as essence in group $gid.")
                else ToolResult(toAgent = "Failed to set message $ref as essence (permission denied or message not found).")
            }
            "remove" -> {
                val ref = args["messageRef"]?.jsonPrimitive?.content?.trim()
                    ?: return ToolResult(toAgent = "Error: missing 'messageRef'.")
                val source = sourceCache[ref]
                    ?: return ToolResult(toAgent = "Error: messageRef '$ref' not found in cache.")
                try {
                    group.essences.remove(source)
                    ToolResult(toAgent = "Removed message $ref from essence in group $gid.")
                } catch (e: Exception) {
                    ToolResult(toAgent = "Error removing essence: ${e.message}")
                }
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }
}

// ─── roaming_messages ──────────────────────────────────────

class RoamingMessagesTool(bot: Bot) : MiraiTool(bot) {
    override val name = "roaming_messages"
    override val description =
        "Query roaming (historical) messages for a friend or group. " +
            "Pass chatType, targetId, and optional timeStart/timeEnd (epoch seconds). " +
            "Returns message history from server."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("chatType") {
                put("type", "string")
                put("description", "'group' or 'friend'.")
                put("enum", buildJsonArray { add(JsonPrimitive("group")); add(JsonPrimitive("friend")) })
            }
            putJsonObject("targetId") {
                put("type", "string")
                put("description", "Group id or friend QQ number.")
            }
            putJsonObject("timeStart") {
                put("type", "string")
                put("description", "Start time in epoch seconds (default: 0 = earliest).")
            }
            putJsonObject("timeEnd") {
                put("type", "string")
                put("description", "End time in epoch seconds (default: now).")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Max messages to return (default 50, max 200).")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("chatType")); add(JsonPrimitive("targetId")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val chatType = args["chatType"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'chatType'.")
        val tid = args["targetId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'targetId'.")
        val timeStart = args["timeStart"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val timeEnd = args["timeEnd"]?.jsonPrimitive?.content?.toLongOrNull() ?: Long.MAX_VALUE
        val limit = (args["limit"]?.jsonPrimitive?.int ?: 50).coerceAtMost(200)

        val roaming = when (chatType) {
            "group" -> {
                val group = bot.getGroup(tid) ?: return ToolResult(toAgent = "Error: group $tid not found.")
                group.roamingMessages
            }
            "friend" -> {
                val friend = bot.getFriend(tid) ?: return ToolResult(toAgent = "Error: friend $tid not found.")
                friend.roamingMessages
            }
            else -> return ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }

        return try {
            val messages = mutableListOf<String>()
            roaming.getMessagesIn(timeStart, timeEnd).collect { chain ->
                if (messages.size >= limit) return@collect
                val source = chain.source
                val senderName = source.fromId.toString()
                val time = java.time.Instant.ofEpochSecond(source.time.toLong()).toString()
                val text = chain.filterIsInstance<net.mamoe.mirai.message.data.PlainText>()
                    .joinToString("") { it.content }
                messages.add("[$time] from=$senderName: ${text.take(200)}")
            }
            if (messages.isEmpty()) {
                ToolResult(toAgent = "No roaming messages found for $chatType $tid in the specified time range.")
            } else {
                ToolResult(toAgent = "Roaming messages for $chatType $tid (${messages.size} messages):\n${messages.joinToString("\n")}")
            }
        } catch (e: Exception) {
            ToolResult(toAgent = "Error querying roaming messages: ${e.message}")
        }
    }
}

// ─── group_files ───────────────────────────────────────────

class GroupFilesTool(bot: Bot) : MiraiTool(bot) {
    override val name = "group_files"
    override val description =
        "Manage group files: list/info/delete/rename/url."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groupId") {
                put("type", "string")
                put("description", "The QQ group id.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "'list', 'info', 'delete', 'rename', or 'url'.")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list")); add(JsonPrimitive("info")); add(JsonPrimitive("delete"))
                    add(JsonPrimitive("rename")); add(JsonPrimitive("url"))
                })
            }
            putJsonObject("fileId") {
                put("type", "string")
                put("description", "File or folder id (for info/delete/rename/url).")
            }
            putJsonObject("folderId") {
                put("type", "string")
                put("description", "Folder id to list (default root '/').")
            }
            putJsonObject("newName") {
                put("type", "string")
                put("description", "New name for rename action.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("groupId")); add(JsonPrimitive("action")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val gid = args["groupId"]?.jsonPrimitive?.long
            ?: return ToolResult(toAgent = "Error: missing 'groupId'.")
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'action'.")
        val group = bot.getGroup(gid) ?: return ToolResult(toAgent = "Error: group $gid not found.")

        return when (action) {
            "list" -> {
                val folderId = args["folderId"]?.jsonPrimitive?.content ?: "/"
                val folder = if (folderId == "/") {
                    group.files.root
                } else {
                    group.files.root.resolveFolderById(folderId)
                        ?: return ToolResult(toAgent = "Error: folder $folderId not found.")
                }
                val children = mutableListOf<net.mamoe.mirai.contact.file.AbsoluteFileFolder>()
                folder.children().collect { children.add(it) }
                if (children.isEmpty()) {
                    ToolResult(toAgent = "Folder '${folder.name}' is empty.")
                } else {
                    val lines = children.map { f ->
                        val type = if (f is net.mamoe.mirai.contact.file.AbsoluteFile) "file" else "folder"
                        val size = if (f is net.mamoe.mirai.contact.file.AbsoluteFile) "size=${f.size}" else "contents=${(f as net.mamoe.mirai.contact.file.AbsoluteFolder).contentsCount}"
                        "id=${f.id} | $type | ${f.name} | $size | uploader=${f.uploaderId}"
                    }
                    ToolResult(toAgent = "Files in '${folder.name}' (${lines.size} items):\n${lines.joinToString("\n")}")
                }
            }
            "info" -> {
                val fileId = args["fileId"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true)
                    ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                ToolResult(
                    toAgent = buildString {
                        appendLine("ID: ${file.id}")
                        appendLine("Name: ${file.name}")
                        appendLine("Path: ${file.absolutePath}")
                        appendLine("Size: ${file.size} bytes")
                        appendLine("Upload time: ${java.time.Instant.ofEpochSecond(file.uploadTime)}")
                        appendLine("Uploader: ${file.uploaderId}")
                    }
                )
            }
            "delete" -> {
                val fileId = args["fileId"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true)
                    ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                val ok = file.delete()
                if (ok) ToolResult(toAgent = "Deleted file ${file.name} (${file.id}) from group $gid.")
                else ToolResult(toAgent = "Failed to delete file ${file.id}.")
            }
            "rename" -> {
                val fileId = args["fileId"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'fileId'.")
                val newName = args["newName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return ToolResult(toAgent = "Error: missing 'newName'.")
                val file = group.files.root.resolveFileById(fileId, deep = true)
                    ?: group.files.root.resolveFolderById(fileId)
                    ?: return ToolResult(toAgent = "Error: file/folder $fileId not found.")
                val ok = file.renameTo(newName)
                if (ok) ToolResult(toAgent = "Renamed to '$newName' (was '${file.name}').")
                else ToolResult(toAgent = "Failed to rename file $fileId.")
            }
            "url" -> {
                val fileId = args["fileId"]?.jsonPrimitive?.content
                    ?: return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true)
                    ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                val url = file.getUrl()
                if (url != null) ToolResult(toAgent = "Download URL for ${file.name}: $url")
                else ToolResult(toAgent = "Error: could not get URL for file $fileId (file may have expired).")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }
}

// ─── helpers ─────────────────────────────────────────────────

/** 权限标签，供 LLM 可读。 */
internal fun permLabel(p: MemberPermission): String = when (p) {
    MemberPermission.OWNER -> "OWNER"
    MemberPermission.ADMINISTRATOR -> "ADMIN"
    MemberPermission.MEMBER -> "MEMBER"
}

/** Group.isBotMuted 扩展 (与 mirai 自带的等价，显式定义避免 import 歧义)。 */
private val Group.isBotMuted: Boolean get() = botMuteRemaining != 0
