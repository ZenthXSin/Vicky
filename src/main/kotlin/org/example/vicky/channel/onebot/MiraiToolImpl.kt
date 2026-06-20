package org.example.vicky.channel.onebot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.mamoe.mirai.Bot
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.NormalMember
import net.mamoe.mirai.contact.announcement.AnnouncementParametersBuilder
import net.mamoe.mirai.contact.announcement.OfflineAnnouncement
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.MessageSource
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.plus
import net.mamoe.mirai.message.data.source
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import org.example.vicky.annotations.ToolGroup
import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult
import java.util.concurrent.ConcurrentHashMap

@ToolGroup(name = "mirai")
object MiraiToolImpl {
    lateinit var bot: Bot
    lateinit var messageSourceCache: ConcurrentHashMap<String, MessageSource>
    lateinit var groupWhitelist: MutableSet<String>
    var onGroupWhitelistChanged: (() -> Unit)? = null

    // ─── bot_info ────────────────────────────────────────────

    @VickyTool(name = "bot_info", description = "Get the bot's own info: QQ id, nickname, online status, friend/group count.")
    fun botInfo(): ToolResult = ToolResult(
        toAgent = buildString {
            appendLine("Bot ID: ${bot.id}")
            appendLine("Nick: ${bot.nick}")
            appendLine("Online: ${bot.isOnline}")
            appendLine("Friends: ${bot.friends.size}")
            appendLine("Groups: ${bot.groups.size}")
        }
    )

    // ─── contacts ────────────────────────────────────────────

    @VickyTool(name = "contacts", description = "List bot's contacts. Pass type='friends' to list friends, type='groups' to list groups, type='strangers' to list strangers.")
    fun contacts(
        @ToolParam(description = "One of: 'friends', 'groups', 'strangers'. Default 'friends'.", required = false) type: String = "friends",
    ): ToolResult = when (type) {
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

    // ─── group_info ──────────────────────────────────────────

    @VickyTool(name = "group_info", description = "Get detailed info of a group by its QQ group id.")
    fun groupInfo(
        @ToolParam(description = "The QQ group id.") groupId: Long,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val s = group.settings
        return ToolResult(
            toAgent = buildString {
                appendLine("Group ID: ${group.id}")
                appendLine("Name: ${group.name}")
                appendLine("Owner: ${group.owner.id} (${group.owner.nameCardOrNick})")
                appendLine("Members: ${group.members.size}")
                appendLine("Bot Permission: ${permLabel(group.botPermission)}")
                appendLine("Bot Muted: ${group.botMuteRemaining != 0} (remaining ${group.botMuteRemaining}s)")
                appendLine("Mute All: ${s.isMuteAll}")
                appendLine("Allow Member Invite: ${s.isAllowMemberInvite}")
                appendLine("Anonymous Chat: ${s.isAnonymousChatEnabled}")
            }
        )
    }

    // ─── group_members ───────────────────────────────────────

    @VickyTool(name = "group_members", description = "List members of a group with their id, nameCard, permission and mute status.")
    fun groupMembers(
        @ToolParam(description = "The QQ group id.") groupId: Long,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val members = group.members
        if (members.isEmpty()) return ToolResult(toAgent = "Group $groupId has no members (except bot).")
        val lines = members.map { m ->
            val muted = if (m.isMuted) "muted(${m.muteTimeRemaining}s)" else "ok"
            "${m.id} | ${m.nameCardOrNick} | ${permLabel(m.permission)} | $muted"
        }
        return ToolResult(toAgent = "Group ${group.id} (${group.name}) — ${lines.size} members:\n${lines.joinToString("\n")}")
    }

    // ─── user_profile ────────────────────────────────────────

    @VickyTool(name = "user_profile", description = "Query a QQ user's profile: nickname, age, level, sex, sign.")
    suspend fun userProfile(
        @ToolParam(description = "The QQ number of the target user.") targetId: Long,
    ): ToolResult {
        val user = bot.getFriend(targetId)
            ?: bot.groups.firstNotNullOfOrNull { g -> g[targetId] }
            ?: return ToolResult(toAgent = "Error: user $targetId not found as friend or group member.")
        val profile = user.queryProfile()
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

    // ─── send_message ────────────────────────────────────────

    @VickyTool(name = "send_message", description = "Proactively send a text message to a group, friend, or temp-session stranger.")
    suspend fun sendMessage(
        @ToolParam(description = "'group', 'friend', or 'temp' (temp session / stranger).") chatType: String,
        @ToolParam(description = "Group id (for group) or QQ number (for friend/temp).") targetId: Long,
        @ToolParam(description = "The text message to send (max 4500 chars).") content: String,
    ): ToolResult {
        if (content.isBlank()) return ToolResult(toAgent = "Error: missing or empty 'content'.")
        return when (chatType) {
            "group" -> {
                val group = bot.getGroup(targetId) ?: return ToolResult(toAgent = "Error: group $targetId not found.")
                group.sendMessage(content)
                ToolResult(toAgent = "Message sent to group $targetId.", userReply = null)
            }
            "friend" -> {
                val friend = bot.getFriend(targetId) ?: return ToolResult(toAgent = "Error: friend $targetId not found.")
                friend.sendMessage(content)
                ToolResult(toAgent = "Message sent to friend $targetId.", userReply = null)
            }
            "temp" -> {
                val contact = bot.getStranger(targetId) ?: bot.getFriend(targetId)
                    ?: return ToolResult(toAgent = "Error: user $targetId not found as stranger or friend.")
                contact.sendMessage(content)
                ToolResult(toAgent = "Message sent to temp-session user $targetId.", userReply = null)
            }
            else -> ToolResult(toAgent = "Error: chatType must be 'group', 'friend', or 'temp'.")
        }
    }

    // ─── group_manage ────────────────────────────────────────

    @VickyTool(name = "group_manage", description = "Manage group: mute/kick/admin/rename/mute-all/title.")
    suspend fun groupManage(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "One of: mute, unmute, kick, set_admin, rename, mute_all, title.") action: String,
        @ToolParam(description = "Target member QQ (required for mute/unmute/kick/set_admin/title).", required = false) memberId: Long = 0,
        @ToolParam(description = "Mute duration in seconds (for 'mute', default 600).", required = false) durationSeconds: Int = 600,
        @ToolParam(description = "Kick reason message (for 'kick').", required = false) message: String = "",
        @ToolParam(description = "Whether to blacklist after kick (for 'kick', default false).", required = false) block: Boolean = false,
        @ToolParam(description = "New group name (for 'rename'), admin grant (true/false for 'set_admin'), mute-all toggle (true/false for 'mute_all'), or special title text (for 'title').", required = false) value: String = "",
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val resolveMember: () -> NormalMember? = { if (memberId != 0L) group[memberId] else null }

        return when (action) {
            "mute" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                m.mute(durationSeconds)
                ToolResult(toAgent = "Muted ${m.id} for ${durationSeconds}s.")
            }
            "unmute" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                m.unmute()
                ToolResult(toAgent = "Unmuted ${m.id}.")
            }
            "kick" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                m.kick(message, block)
                ToolResult(toAgent = "Kicked ${m.id} (block=$block, msg='$message').")
            }
            "set_admin" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                val grant = value.toBooleanStrictOrNull()
                    ?: return ToolResult(toAgent = "Error: 'value' must be 'true' or 'false' for set_admin.")
                m.modifyAdmin(grant)
                ToolResult(toAgent = "Set admin=$grant for ${m.id}.")
            }
            "rename" -> {
                if (value.isBlank()) return ToolResult(toAgent = "Error: missing 'value' (new group name).")
                // Note: Group.name is read-only in this Mirai version. Rename not supported.
                ToolResult(toAgent = "Error: group rename is not supported in this Mirai version.")
            }
            "mute_all" -> {
                val enable = value.toBooleanStrictOrNull()
                    ?: return ToolResult(toAgent = "Error: 'value' must be 'true' or 'false' for mute_all.")
                group.settings.isMuteAll = enable
                ToolResult(toAgent = "Mute-all set to $enable for group $groupId.")
            }
            "title" -> {
                val m = resolveMember() ?: return ToolResult(toAgent = "Error: missing or invalid 'memberId'.")
                if (value.isBlank()) return ToolResult(toAgent = "Error: missing 'value' (special title text).")
                m.specialTitle = value
                ToolResult(toAgent = "Set special title '$value' for ${m.id}.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }

    // ─── friend_manage ───────────────────────────────────────

    @VickyTool(name = "friend_manage", description = "Manage a friend. Actions: 'delete' (remove and block friend), 'remark' (change remark/note name).")
    suspend fun friendManage(
        @ToolParam(description = "Friend's QQ number.") friendId: Long,
        @ToolParam(description = "'delete' or 'remark'.") action: String,
        @ToolParam(description = "New remark text (required for action='remark').", required = false) remark: String = "",
    ): ToolResult {
        val friend = bot.getFriend(friendId) ?: return ToolResult(toAgent = "Error: friend $friendId not found.")
        return when (action) {
            "delete" -> {
                friend.delete()
                ToolResult(toAgent = "Deleted and blocked friend $friendId.")
            }
            "remark" -> {
                if (remark.isBlank()) return ToolResult(toAgent = "Error: missing 'remark' text.")
                friend.remark = remark
                ToolResult(toAgent = "Set remark '$remark' for friend $friendId.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'. Use 'delete' or 'remark'.")
        }
    }

    // ─── group_quit ──────────────────────────────────────────

    @VickyTool(name = "group_quit", description = "Quit (leave) a group. Bot must NOT be the owner.")
    suspend fun groupQuit(
        @ToolParam(description = "The QQ group id to quit.") groupId: Long,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        return try {
            val ok = group.quit()
            if (ok) ToolResult(toAgent = "Quit group $groupId successfully.")
            else ToolResult(toAgent = "Already left group $groupId or quit failed.")
        } catch (e: IllegalStateException) {
            ToolResult(toAgent = "Error: cannot quit group $groupId — ${e.message} (bot may be the owner).")
        }
    }

    // ─── group_announcements ─────────────────────────────────

    @VickyTool(name = "group_announcements", description = "Manage group announcements: list/publish/delete.")
    suspend fun groupAnnouncements(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "'list', 'publish', or 'delete'.") action: String,
        @ToolParam(description = "Announcement text content (required for 'publish').", required = false) content: String = "",
        @ToolParam(description = "Announcement fid (required for 'delete').", required = false) fid: String = "",
        @ToolParam(description = "Pin the announcement (for 'publish', default false).", required = false) pinned: Boolean = false,
        @ToolParam(description = "Require members to confirm (for 'publish', default false).", required = false) needConfirm: Boolean = false,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val announcements = group.announcements

        return when (action) {
            "list" -> {
                val list = announcements.toList()
                if (list.isEmpty()) return ToolResult(toAgent = "Group $groupId has no announcements.")
                val lines = list.map { a ->
                    val sender = a.sender?.nameCardOrNick ?: a.senderId.toString()
                    "fid=${a.fid} | by=$sender | confirmed=${a.confirmedMembersCount} | pinned=${a.parameters.isPinned}\n  ${a.content.take(120)}"
                }
                ToolResult(toAgent = "Group $groupId announcements (${lines.size}):\n${lines.joinToString("\n")}")
            }
            "publish" -> {
                if (content.isBlank()) return ToolResult(toAgent = "Error: missing or empty 'content'.")
                val params = AnnouncementParametersBuilder().apply {
                    isPinned = pinned; requireConfirmation = needConfirm
                }.build()
                val online = announcements.publish(OfflineAnnouncement(content, params))
                ToolResult(toAgent = "Published announcement fid=${online.fid} to group $groupId.")
            }
            "delete" -> {
                if (fid.isBlank()) return ToolResult(toAgent = "Error: missing 'fid'.")
                val ok = announcements.delete(fid)
                if (ok) ToolResult(toAgent = "Deleted announcement $fid from group $groupId.")
                else ToolResult(toAgent = "Announcement $fid not found in group $groupId.")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }

    // ─── at_member ───────────────────────────────────────────

    @VickyTool(name = "at_member", description = "Send a message that @mentions a user in a group.")
    suspend fun atMember(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "QQ number of the user to @mention.") targetId: Long,
        @ToolParam(description = "Optional text to send after the @mention.", required = false) text: String = "",
        @ToolParam(description = "If true, end the bot's turn after sending the @mention. Default false.", required = false) endTurn: Boolean = false,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val member = group[targetId] ?: return ToolResult(toAgent = "Error: member $targetId not found in group $groupId.")
        val msg = At(targetId) + if (text.isNotBlank()) " $text" else ""
        group.sendMessage(msg)
        return ToolResult(toAgent = "Sent @${member.nameCardOrNick}($targetId) in group $groupId.", userReply = null, endTurn = endTurn)
    }

    // ─── reply_message ───────────────────────────────────────

    @VickyTool(name = "reply_message", description = "Reply (quote) to a specific message. Pass the messageRef seen in chat history.")
    suspend fun replyMessage(
        @ToolParam(description = "Message reference id from chat history, e.g. '#42'.") messageRef: String,
        @ToolParam(description = "Group id or friend QQ to send the reply to.") targetId: Long,
        @ToolParam(description = "The reply text content.") text: String,
        @ToolParam(description = "'group' or 'friend'. Default 'group'.", required = false) chatType: String = "group",
        @ToolParam(description = "If true, end the bot's turn after sending the reply. Default true.", required = false) endTurn: Boolean = true,
    ): ToolResult {
        if (text.isBlank()) return ToolResult(toAgent = "Error: missing or empty 'text'.")
        val source = messageSourceCache[messageRef.trim()]
            ?: return ToolResult(toAgent = "Error: messageRef '${messageRef.trim()}' not found in cache. It may have expired.")
        val msg = QuoteReply(source) + text

        return when (chatType) {
            "group" -> {
                val group = bot.getGroup(targetId) ?: return ToolResult(toAgent = "Error: group $targetId not found.")
                group.sendMessage(msg)
                ToolResult(toAgent = "Replied to $messageRef in group $targetId.", userReply = null, endTurn = endTurn)
            }
            "friend" -> {
                val friend = bot.getFriend(targetId) ?: return ToolResult(toAgent = "Error: friend $targetId not found.")
                friend.sendMessage(msg)
                ToolResult(toAgent = "Replied to $messageRef in friend chat $targetId.", userReply = null, endTurn = endTurn)
            }
            else -> ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }
    }

    // ─── recall_message ─────────────────────────────────────

    @VickyTool(name = "recall_message", description = "Recall (retract) a sent message. Pass the messageRef seen in chat history.")
    suspend fun recallMessage(
        @ToolParam(description = "Message reference id from chat history, e.g. '#42'.") messageRef: String,
    ): ToolResult {
        val source = messageSourceCache[messageRef.trim()]
            ?: return ToolResult(toAgent = "Error: messageRef '${messageRef.trim()}' not found in cache. It may have expired.")
        return try {
            Mirai.recallMessage(bot, source)
            ToolResult(toAgent = "Recalled message $messageRef.")
        } catch (e: Exception) {
            ToolResult(toAgent = "Error recalling message $messageRef: ${e.message}")
        }
    }

    // ─── send_image ──────────────────────────────────────────

    @VickyTool(name = "send_image", description = "Send an image to a group, friend, or temp-session user.")
    suspend fun sendImage(
        @ToolParam(description = "'group', 'friend', or 'temp'.") chatType: String,
        @ToolParam(description = "Group id or QQ number.") targetId: Long,
        @ToolParam(description = "Direct URL to the image file.") imageUrl: String,
    ): ToolResult {
        if (imageUrl.isBlank()) return ToolResult(toAgent = "Error: missing or empty 'imageUrl'.")
        val contact = when (chatType) {
            "group" -> bot.getGroup(targetId) ?: return ToolResult(toAgent = "Error: group $targetId not found.")
            "friend" -> bot.getFriend(targetId) ?: return ToolResult(toAgent = "Error: friend $targetId not found.")
            "temp" -> bot.getStranger(targetId) ?: bot.getFriend(targetId)
                ?: return ToolResult(toAgent = "Error: user $targetId not found.")
            else -> return ToolResult(toAgent = "Error: chatType must be 'group', 'friend', or 'temp'.")
        }
        return try {
            val image = java.net.URL(imageUrl).openStream().use { stream ->
                stream.toExternalResource().use { resource -> contact.uploadImage(resource) }
            }
            contact.sendMessage(image)
            ToolResult(toAgent = "Image sent to $chatType $targetId.", userReply = null)
        } catch (e: Exception) {
            ToolResult(toAgent = "Error sending image: ${e.message}")
        }
    }

    // ─── send_video ──────────────────────────────────────────

    @VickyTool(name = "send_video", description = "Send a short video to a group or friend.")
    suspend fun sendVideo(
        @ToolParam(description = "'group' or 'friend'.") chatType: String,
        @ToolParam(description = "Group id or QQ number.") targetId: Long,
        @ToolParam(description = "Direct URL to the video file (mp4).") videoUrl: String,
        @ToolParam(description = "Direct URL to the thumbnail image. Optional.", required = false) thumbnailUrl: String = "",
    ): ToolResult {
        if (videoUrl.isBlank()) return ToolResult(toAgent = "Error: missing or empty 'videoUrl'.")
        val contact = when (chatType) {
            "group" -> bot.getGroup(targetId) ?: return ToolResult(toAgent = "Error: group $targetId not found.")
            "friend" -> bot.getFriend(targetId) ?: return ToolResult(toAgent = "Error: friend $targetId not found.")
            else -> return ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }
        return try {
            val videoResource = java.net.URL(videoUrl).openStream().use { it.toExternalResource() }
            val thumbnailResource = if (thumbnailUrl.isNotBlank()) {
                java.net.URL(thumbnailUrl).openStream().use { it.toExternalResource() }
            } else {
                java.net.URL(videoUrl).openStream().use { it.toExternalResource() }
            }
            val shortVideo = contact.uploadShortVideo(thumbnailResource, videoResource)
            contact.sendMessage(shortVideo)
            ToolResult(toAgent = "Video sent to $chatType $targetId.", userReply = null)
        } catch (e: Exception) {
            ToolResult(toAgent = "Error sending video: ${e.message}")
        }
    }

    // ─── friend_request ──────────────────────────────────────

    @VickyTool(name = "friend_request", description = "Manage friend requests. Actions: 'list', 'accept', 'reject'.")
    suspend fun friendRequest(
        @ToolParam(description = "'list', 'accept', or 'reject'.") action: String,
        @ToolParam(description = "Event id (required for accept/reject).", required = false) eventId: Long = 0,
        @ToolParam(description = "Whether to blacklist after reject (default false).", required = false) blacklist: Boolean = false,
    ): ToolResult = when (action) {
        "list" -> {
            val events = mutableListOf<net.mamoe.mirai.event.events.NewFriendRequestEvent>()
            bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.NewFriendRequestEvent> { events.add(this) }
            if (events.isEmpty()) ToolResult(toAgent = "No pending friend requests.")
            else {
                val lines = events.map { e -> "eventId=${e.eventId} | from=${e.fromId}(${e.fromNick}) | msg='${e.message}' | group=${e.fromGroupId}" }
                ToolResult(toAgent = "Pending friend requests (${events.size}):\n${lines.joinToString("\n")}")
            }
        }
        "accept" -> {
            if (eventId == 0L) return ToolResult(toAgent = "Error: missing 'eventId'.")
            try {
                var found: net.mamoe.mirai.event.events.NewFriendRequestEvent? = null
                bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.NewFriendRequestEvent> { if (this.eventId == eventId) found = this }
                val event = found ?: return ToolResult(toAgent = "Error: friend request event $eventId not found.")
                event.accept()
                ToolResult(toAgent = "Accepted friend request from ${event.fromId}(${event.fromNick}).")
            } catch (e: Exception) { ToolResult(toAgent = "Error accepting friend request: ${e.message}") }
        }
        "reject" -> {
            if (eventId == 0L) return ToolResult(toAgent = "Error: missing 'eventId'.")
            try {
                var found: net.mamoe.mirai.event.events.NewFriendRequestEvent? = null
                bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.NewFriendRequestEvent> { if (this.eventId == eventId) found = this }
                val event = found ?: return ToolResult(toAgent = "Error: friend request event $eventId not found.")
                event.reject(blacklist)
                ToolResult(toAgent = "Rejected friend request from ${event.fromId}(${event.fromNick}), blacklist=$blacklist.")
            } catch (e: Exception) { ToolResult(toAgent = "Error rejecting friend request: ${e.message}") }
        }
        else -> ToolResult(toAgent = "Error: unknown action '$action'.")
    }

    // ─── group_invite ────────────────────────────────────────

    @VickyTool(name = "group_invite", description = "Manage group invitations (bot invited to join group).")
    suspend fun groupInvite(
        @ToolParam(description = "'accept' or 'ignore'.") action: String,
        @ToolParam(description = "Event id of the invitation.") eventId: Long,
    ): ToolResult {
        return try {
            var found: net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent? = null
            bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent> { if (this.eventId == eventId) found = this }
            val event = found ?: return ToolResult(toAgent = "Error: group invite event $eventId not found.")
            when (action) {
                "accept" -> { event.accept(); ToolResult(toAgent = "Accepted group invite from ${event.invitorId}(${event.invitorNick}) to group ${event.groupId}(${event.groupName}).") }
                "ignore" -> { event.ignore(); ToolResult(toAgent = "Ignored group invite from ${event.invitorId}(${event.invitorNick}) to group ${event.groupId}(${event.groupName}).") }
                else -> ToolResult(toAgent = "Error: unknown action '$action'.")
            }
        } catch (e: Exception) { ToolResult(toAgent = "Error handling group invite: ${e.message}") }
    }

    // ─── member_join_request ─────────────────────────────────

    @VickyTool(name = "member_join_request", description = "Manage group join requests. Actions: 'accept', 'reject', 'ignore'.")
    suspend fun memberJoinRequest(
        @ToolParam(description = "'accept', 'reject', or 'ignore'.") action: String,
        @ToolParam(description = "Event id of the join request.") eventId: Long,
        @ToolParam(description = "Whether to blacklist after reject (default false).", required = false) blacklist: Boolean = false,
        @ToolParam(description = "Rejection message (for reject action).", required = false) message: String = "",
    ): ToolResult {
        return try {
            var found: net.mamoe.mirai.event.events.MemberJoinRequestEvent? = null
            bot.eventChannel.subscribeAlways<net.mamoe.mirai.event.events.MemberJoinRequestEvent> { if (this.eventId == eventId) found = this }
            val event = found ?: return ToolResult(toAgent = "Error: member join request event $eventId not found.")
            when (action) {
                "accept" -> { event.accept(); ToolResult(toAgent = "Accepted join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}(${event.groupName}).") }
                "reject" -> { event.reject(blacklist, message); ToolResult(toAgent = "Rejected join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}.") }
                "ignore" -> { event.ignore(blacklist); ToolResult(toAgent = "Ignored join request from ${event.fromId}(${event.fromNick}) to group ${event.groupId}.") }
                else -> ToolResult(toAgent = "Error: unknown action '$action'.")
            }
        } catch (e: Exception) { ToolResult(toAgent = "Error handling member join request: ${e.message}") }
    }

    // ─── set_name_card ───────────────────────────────────────

    @VickyTool(name = "set_name_card", description = "Set a member's group name card. Requires admin or owner permission.")
    suspend fun setNameCard(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "Target member QQ number.") memberId: Long,
        @ToolParam(description = "New name card text (empty string to reset).") nameCard: String,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        val member = group[memberId] ?: return ToolResult(toAgent = "Error: member $memberId not found in group $groupId.")
        return try {
            member.nameCard = nameCard
            val display = if (nameCard.isEmpty()) "(reset to default)" else "'$nameCard'"
            ToolResult(toAgent = "Set name card for ${member.id}(${member.nameCardOrNick}) in group $groupId to $display.")
        } catch (e: Exception) { ToolResult(toAgent = "Error setting name card: ${e.message}") }
    }

    // ─── essence_message ─────────────────────────────────────

    @VickyTool(name = "essence_message", description = "Manage group essence messages. Actions: 'list', 'add', 'remove'.")
    suspend fun essenceMessage(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "'list', 'add', or 'remove'.") action: String,
        @ToolParam(description = "Message reference id from chat history, e.g. '#42' (for add/remove).", required = false) messageRef: String = "",
        @ToolParam(description = "Start index for pagination (for list, default 0).", required = false) start: Int = 0,
        @ToolParam(description = "Page size (for list, default 20, max 50).", required = false) limit: Int = 20,
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        return when (action) {
            "list" -> {
                val records = group.essences.getPage(start, limit.coerceAtMost(50))
                if (records.isEmpty()) ToolResult(toAgent = "Group $groupId has no essence messages (or page exhausted).")
                else {
                    val lines = records.map { r ->
                        val sender = r.sender?.nameCardOrNick ?: r.senderId.toString()
                        "sender=$sender(${r.senderId}) | time=${r.senderTime} | operator=${r.operatorId}"
                    }
                    ToolResult(toAgent = "Group $groupId essence messages (start=$start, ${lines.size} results):\n${lines.joinToString("\n")}")
                }
            }
            "add" -> {
                if (messageRef.isBlank()) return ToolResult(toAgent = "Error: missing 'messageRef'.")
                val source = messageSourceCache[messageRef.trim()] ?: return ToolResult(toAgent = "Error: messageRef '${messageRef.trim()}' not found in cache.")
                val ok = group.setEssenceMessage(source)
                if (ok) ToolResult(toAgent = "Set message $messageRef as essence in group $groupId.")
                else ToolResult(toAgent = "Failed to set message $messageRef as essence.")
            }
            "remove" -> {
                if (messageRef.isBlank()) return ToolResult(toAgent = "Error: missing 'messageRef'.")
                val source = messageSourceCache[messageRef.trim()] ?: return ToolResult(toAgent = "Error: messageRef '${messageRef.trim()}' not found in cache.")
                try { group.essences.remove(source); ToolResult(toAgent = "Removed message $messageRef from essence in group $groupId.") }
                catch (e: Exception) { ToolResult(toAgent = "Error removing essence: ${e.message}") }
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }

    // ─── roaming_messages ────────────────────────────────────

    @VickyTool(name = "roaming_messages", description = "Query roaming (historical) messages for a friend or group.")
    suspend fun roamingMessages(
        @ToolParam(description = "'group' or 'friend'.") chatType: String,
        @ToolParam(description = "Group id or friend QQ number.") targetId: Long,
        @ToolParam(description = "Start time in epoch seconds (default: 0 = earliest).", required = false) timeStart: String = "0",
        @ToolParam(description = "End time in epoch seconds (default: now).", required = false) timeEnd: String = "",
        @ToolParam(description = "Max messages to return (default 50, max 200).", required = false) limit: Int = 50,
    ): ToolResult {
        val start = timeStart.toLongOrNull() ?: 0L
        val end = timeEnd.toLongOrNull() ?: Long.MAX_VALUE
        val max = limit.coerceAtMost(200)

        val roaming = when (chatType) {
            "group" -> bot.getGroup(targetId)?.roamingMessages ?: return ToolResult(toAgent = "Error: group $targetId not found.")
            "friend" -> bot.getFriend(targetId)?.roamingMessages ?: return ToolResult(toAgent = "Error: friend $targetId not found.")
            else -> return ToolResult(toAgent = "Error: chatType must be 'group' or 'friend'.")
        }
        return try {
            val messages = mutableListOf<String>()
            roaming.getMessagesIn(start, end).collect { chain ->
                if (messages.size >= max) return@collect
                val source = chain.source
                val time = java.time.Instant.ofEpochSecond(source.time.toLong()).toString()
                val text = chain.filterIsInstance<PlainText>().joinToString("") { it.content }
                messages.add("[$time] from=${source.fromId}: ${text.take(200)}")
            }
            if (messages.isEmpty()) ToolResult(toAgent = "No roaming messages found for $chatType $targetId.")
            else ToolResult(toAgent = "Roaming messages for $chatType $targetId (${messages.size} messages):\n${messages.joinToString("\n")}")
        } catch (e: Exception) { ToolResult(toAgent = "Error querying roaming messages: ${e.message}") }
    }

    // ─── group_files ─────────────────────────────────────────

    @VickyTool(name = "group_files", description = "Manage group files: list/info/delete/rename/url.")
    suspend fun groupFiles(
        @ToolParam(description = "The QQ group id.") groupId: Long,
        @ToolParam(description = "'list', 'info', 'delete', 'rename', or 'url'.") action: String,
        @ToolParam(description = "File or folder id (for info/delete/rename/url).", required = false) fileId: String = "",
        @ToolParam(description = "Folder id to list (default root '/').", required = false) folderId: String = "/",
        @ToolParam(description = "New name for rename action.", required = false) newName: String = "",
    ): ToolResult {
        val group = bot.getGroup(groupId) ?: return ToolResult(toAgent = "Error: group $groupId not found.")
        return when (action) {
            "list" -> {
                val folder = if (folderId == "/") group.files.root
                else group.files.root.resolveFolderById(folderId) ?: return ToolResult(toAgent = "Error: folder $folderId not found.")
                val children = mutableListOf<net.mamoe.mirai.contact.file.AbsoluteFileFolder>()
                folder.children().collect { children.add(it) }
                if (children.isEmpty()) ToolResult(toAgent = "Folder '${folder.name}' is empty.")
                else {
                    val lines = children.map { f ->
                        val type = if (f is net.mamoe.mirai.contact.file.AbsoluteFile) "file" else "folder"
                        val size = if (f is net.mamoe.mirai.contact.file.AbsoluteFile) "size=${f.size}" else "contents=${(f as net.mamoe.mirai.contact.file.AbsoluteFolder).contentsCount}"
                        "id=${f.id} | $type | ${f.name} | $size | uploader=${f.uploaderId}"
                    }
                    ToolResult(toAgent = "Files in '${folder.name}' (${lines.size} items):\n${lines.joinToString("\n")}")
                }
            }
            "info" -> {
                if (fileId.isBlank()) return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true) ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                ToolResult(toAgent = buildString {
                    appendLine("ID: ${file.id}"); appendLine("Name: ${file.name}"); appendLine("Path: ${file.absolutePath}")
                    appendLine("Size: ${file.size} bytes"); appendLine("Upload time: ${java.time.Instant.ofEpochSecond(file.uploadTime)}"); appendLine("Uploader: ${file.uploaderId}")
                })
            }
            "delete" -> {
                if (fileId.isBlank()) return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true) ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                if (file.delete()) ToolResult(toAgent = "Deleted file ${file.name} (${file.id}) from group $groupId.")
                else ToolResult(toAgent = "Failed to delete file ${file.id}.")
            }
            "rename" -> {
                if (fileId.isBlank()) return ToolResult(toAgent = "Error: missing 'fileId'.")
                if (newName.isBlank()) return ToolResult(toAgent = "Error: missing 'newName'.")
                val file = group.files.root.resolveFileById(fileId, deep = true)
                    ?: group.files.root.resolveFolderById(fileId)
                    ?: return ToolResult(toAgent = "Error: file/folder $fileId not found.")
                if (file.renameTo(newName)) ToolResult(toAgent = "Renamed to '$newName' (was '${file.name}').")
                else ToolResult(toAgent = "Failed to rename file $fileId.")
            }
            "url" -> {
                if (fileId.isBlank()) return ToolResult(toAgent = "Error: missing 'fileId'.")
                val file = group.files.root.resolveFileById(fileId, deep = true) ?: return ToolResult(toAgent = "Error: file $fileId not found.")
                val url = file.getUrl()
                if (url != null) ToolResult(toAgent = "Download URL for ${file.name}: $url")
                else ToolResult(toAgent = "Error: could not get URL for file $fileId (file may have expired).")
            }
            else -> ToolResult(toAgent = "Error: unknown action '$action'.")
        }
    }

    // ─── get_messages ────────────────────────────────────────

    @VickyTool(name = "get_messages", description = "Query the message buffer for this conversation.")
    fun getMessages(
        ctx: ToolContext,
        @ToolParam(description = "'all' = all buffered messages, 'unread' = only new since last query.") mode: String = "all",
        @ToolParam(description = "'text' = plain text, 'media' = rich media, 'raw' = original message.") type: String = "text",
    ): ToolResult {
        val buf = ctx.buffer ?: return ToolResult(toAgent = "Error: message buffer is not available in this context.")
        val convId = ctx.conversationId
        val unread = mode == "unread"
        val result = when (type) {
            "text" -> formatText(buf.getText(convId, unread))
            "media" -> formatMedia(buf.getRichMedia(convId, unread))
            "raw" -> formatRaw(buf.getRaw(convId, unread))
            else -> "Error: unknown type '$type'. Use 'text', 'media', or 'raw'."
        }
        if (unread) buf.markRead(convId)
        return ToolResult(toAgent = result)
    }

    // ─── group_whitelist_add ─────────────────────────────────

    @VickyTool(name = "group_whitelist_add", description = "Add the current group to the OneBot reply whitelist and persist to config.json. Admin-only.")
    fun groupWhitelistAdd(ctx: ToolContext): ToolResult {
        val gid = ctx.groupId
        if (gid.isBlank()) return ToolResult(toAgent = "Error: not in a group conversation.")
        return if (groupWhitelist.add(gid)) {
            onGroupWhitelistChanged?.invoke()
            ToolResult(toAgent = "added group $gid to whitelist", userReply = "已加入白名单：$gid")
        } else {
            ToolResult(toAgent = "group $gid already in whitelist", userReply = "已在白名单中")
        }
    }

    // ─── helpers ─────────────────────────────────────────────

    private fun formatText(messages: List<BufferedMessage>): String {
        if (messages.isEmpty()) return "(no text messages in buffer)"
        return messages.joinToString("\n") { "[${it.senderName}(${it.userId})] ${it.text}" }
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
        return messages.joinToString("\n---\n") { "[${it.senderName}(${it.userId})]\n${it.raw}" }
    }
}

internal fun permLabel(p: MemberPermission): String = when (p) {
    MemberPermission.OWNER -> "OWNER"
    MemberPermission.ADMINISTRATOR -> "ADMIN"
    MemberPermission.MEMBER -> "MEMBER"
}
