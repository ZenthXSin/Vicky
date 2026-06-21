package org.example.vicky.channel.onebot

import kotlinx.coroutines.withContext
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.EventPriority
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.StrangerMessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.buildForwardMessage
import org.example.vicky.agent.Agent
import org.example.vicky.agent.AgentConfig
import org.example.vicky.generated.ToolRegistry
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolAuthorizer
import top.mrxiaom.overflow.BotBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OneBot(
    var agentConfig: AgentConfig,
    var url: String,
    var token: String,
) {
    val adminList = mutableSetOf<String>()
    val adminToolList = mutableSetOf<String>()
    val groupWhitelist = mutableSetOf<String>()
    val userWhitelist = mutableSetOf<String>()

    /** 当群白名单因管理员指令变更时回调，外部用来持久化到 config.json。 */
    var onGroupWhitelistChanged: ((Set<String>) -> Unit)? = null

    val buffer = MessageBuffer(
        maxGlobalEntries = agentConfig.messageBufferMaxGlobalEntries,
        rawTruncate = agentConfig.messageBufferRawTruncate,
    )

    /** 消息序号计数器，为每条消息分配唯一递增 ID。 */
    private val msgCounter = AtomicLong(0)

    /** 消息源缓存: msgRef -> MessageSource，供 reply_message 工具引用。 */
    val messageSourceCache = ConcurrentHashMap<String, MessageSource>()

    var bot: Bot? = null
    private set

    lateinit var agent: OneBotAgent
        private set

    /** 连接并注册三个事件监听器。 */
    suspend fun connect(): Boolean {
        bot = BotBuilder.positive(url).token(token).connect() ?: return false
        agent = OneBotAgent(agentConfig, bot!!, buffer, adminList, adminToolList)
        // Initialize MiraiToolImpl with dependencies and register all mirai tools
        MiraiToolImpl.bot = bot!!
        MiraiToolImpl.messageSourceCache = messageSourceCache
        MiraiToolImpl.groupWhitelist = groupWhitelist
        MiraiToolImpl.onGroupWhitelistChanged = {
            onGroupWhitelistChanged?.invoke(groupWhitelist.toSet())
        }
        ToolRegistry.tools("mirai").forEach { agent.registerTool(it) }
        // Register mirai L2 tool names as admin-gated
        listOf(
            "send_message", "group_manage", "friend_manage", "group_quit", "group_announcements",
            "file_write", "send_image", "send_video", "recall_message", "set_name_card",
            "essence_message", "group_files", "group_whitelist_add", "manage_tools", "manage_skills"
        ).forEach { adminToolList.add(it) }
        registerListeners()
        return true
    }

    // region 事件监听

    private fun registerListeners() {
        val b = bot ?: return
        val channel = b.eventChannel

        // 监听器A: 全量消息 (群+好友) → 拆分存入缓冲区
        // HIGHEST 优先于 NORMAL，确保缓冲先于触发。
        channel.subscribeAlways<GroupMessageEvent>(priority = EventPriority.HIGHEST) {
            val ref = "#${msgCounter.incrementAndGet()}"
            messageSourceCache[ref] = message.source
            if (messageSourceCache.size > 2000) {
                val toRemove = messageSourceCache.keys.take(200)
                toRemove.forEach { messageSourceCache.remove(it) }
            }
            val (text, richMedia) = parseMessage(message)
            buffer.store(
                conversationId = group.id.toString(),
                message = BufferedMessage(
                    text = text,
                    richMedia = richMedia,
                    raw = message.toString(),
                    userId = sender.id.toString(),
                    senderName = sender.nameCardOrNick,
                    msgRef = ref,
                )
            )
        }
        channel.subscribeAlways<FriendMessageEvent>(priority = EventPriority.HIGHEST) {
            val ref = "#${msgCounter.incrementAndGet()}"
            messageSourceCache[ref] = message.source
            if (messageSourceCache.size > 2000) {
                val toRemove = messageSourceCache.keys.take(200)
                toRemove.forEach { messageSourceCache.remove(it) }
            }
            val (text, richMedia) = parseMessage(message)
            buffer.store(
                conversationId = sender.id.toString(),
                message = BufferedMessage(
                    text = text,
                    richMedia = richMedia,
                    raw = message.toString(),
                    userId = sender.id.toString(),
                    senderName = sender.nick,
                    msgRef = ref,
                )
            )
        }
        // 陌生人/临时会话消息 → 存入缓冲区
        channel.subscribeAlways<StrangerMessageEvent>(priority = EventPriority.HIGHEST) {
            val ref = "#${msgCounter.incrementAndGet()}"
            messageSourceCache[ref] = message.source
            if (messageSourceCache.size > 2000) {
                val toRemove = messageSourceCache.keys.take(200)
                toRemove.forEach { messageSourceCache.remove(it) }
            }
            val (text, richMedia) = parseMessage(message)
            buffer.store(
                conversationId = sender.id.toString(),
                message = BufferedMessage(
                    text = text,
                    richMedia = richMedia,
                    raw = message.toString(),
                    userId = sender.id.toString(),
                    senderName = sender.nick,
                    msgRef = ref,
                )
            )
        }

        // 监听器B: 群消息 → 检测 @Bot → 触发 Agent
        channel.subscribeAlways<GroupMessageEvent> {
            if (!isBotMentioned(message, b)) return@subscribeAlways
            if (group.id.toString() !in groupWhitelist && sender.id.toString() !in adminList) return@subscribeAlways

            val inbound = buildInboundFromBuffer(
                conversationId = group.id.toString(),
                userId = sender.id.toString(),
                groupId = group.id.toString(),
                groupName = group.name,
                currentText = message.content,
                senderName = sender.nameCardOrNick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }

        // 监听器C: 好友消息 → 全部触发 Agent
        channel.subscribeAlways<FriendMessageEvent> {
            if (sender.id.toString() !in userWhitelist && sender.id.toString() !in adminList) return@subscribeAlways
            val inbound = buildInboundFromBuffer(
                conversationId = sender.id.toString(),
                userId = sender.id.toString(),
                groupId = "",
                groupName = "",
                currentText = message.content,
                senderName = sender.nick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }

        // 监听器D: 陌生人/临时会话消息 → 全部触发 Agent
        channel.subscribeAlways<StrangerMessageEvent> {
            if (sender.id.toString() !in userWhitelist && sender.id.toString() !in adminList) return@subscribeAlways
            val inbound = buildInboundFromBuffer(
                conversationId = sender.id.toString(),
                userId = sender.id.toString(),
                groupId = "",
                groupName = "",
                currentText = message.content,
                senderName = sender.nick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }
    }

    // endregion

    // region 消息处理

    /** 拆分 MessageChain 为纯文本 + 富媒体列表。 */
    private suspend fun parseMessage(message: MessageChain): Pair<String, List<RichMediaItem>> {
        val textParts = mutableListOf<String>()
        val media = mutableListOf<RichMediaItem>()

        for (elem in message) {
            when (elem) {
                is PlainText -> if (elem.content.isNotBlank()) textParts += elem.content.trim()
                is Image -> media += RichMediaItem(
                    type = "image",
                    description = elem.imageId,
                    url = runCatching { elem.queryUrl() }.getOrDefault(""),
                    raw = elem.toString(),
                )
                is FlashImage -> media += RichMediaItem(
                    type = "flashImage",
                    description = elem.image.imageId,
                    url = runCatching { elem.image.queryUrl() }.getOrDefault(""),
                    raw = elem.toString(),
                )
                // At / AtAll / Face 等不纳入纯文本也不纳入富媒体
                else -> {}
            }
        }
        return textParts.joinToString(" ") to media
    }

    /** 检测消息中是否 @Bot。 */
    private fun isBotMentioned(message: MessageChain, bot: Bot): Boolean =
        message.any { it is At && it.target == bot.id }

    /**
     * 从缓冲区构建 InboundMessage：
     * 注入群/好友元数据 + 近期文本上下文 + 当前消息，
     * 让 Agent 能看到聊天环境和前因后果。
     */
    private fun buildInboundFromBuffer(
        conversationId: String,
        userId: String,
        groupId: String,
        groupName: String,
        currentText: String,
        senderName: String,
    ): InboundMessage {
        val recentText = buffer.getText(conversationId).takeLast(15)
        val sb = StringBuilder()
        // 环境元数据
        if (groupId.isNotEmpty()) {
            sb.appendLine("[群聊: $groupName($groupId)]")
        } else {
            sb.appendLine("[私聊]")
        }
        // 近期聊天记录
        if (recentText.isNotEmpty()) {
            sb.appendLine("--- 近期聊天记录 ---")
            recentText.forEach {
                val refTag = if (it.msgRef.isNotEmpty()) "${it.msgRef} " else ""
                val truncated = if (it.text.length > 200) it.text.take(200) + "…" else it.text
                sb.appendLine("$refTag[${it.senderName}:${it.userId}] $truncated")
            }
            sb.appendLine("--- 当前消息 ---")
        }
        sb.append("[$senderName:$userId] $currentText")
        return InboundMessage(
            userId = userId,
            content = sb.toString(),
            conversationId = conversationId,
            groupId = groupId,
        )
    }

    // endregion

    // region Agent 子类

    class OneBotAgent(
        config: AgentConfig,
        private val bot: Bot,
        override val buffer: MessageBuffer,
        private val adminList: Set<String>,
        private val adminToolList: Set<String>,
    ) : Agent(config) {

        private suspend fun sendSmart(target: net.mamoe.mirai.contact.Contact, text: String) {
            if (text.length > 100) {
                target.sendMessage(buildForwardMessage(target) {
                    add(target.id, bot.nick, PlainText(text))
                })
            } else {
                target.sendMessage(text)
            }
        }

        override val sink = MessageSink { out ->
            when (out) {
                is OutboundMessage.AgentReply -> {
                    val text = out.content
                    when {
                        out.groupId.isNotEmpty() -> {
                            val group = bot.getGroup(out.groupId.toLongOrNull() ?: return@MessageSink)
                                ?: return@MessageSink
                            sendSmart(group, text)
                        }
                        else -> {
                            val uid = out.userId.toLongOrNull() ?: return@MessageSink
                            val contact = bot.getFriend(uid) ?: bot.getStranger(uid)
                                ?: return@MessageSink
                            sendSmart(contact, text)
                        }
                    }
                }
                is OutboundMessage.ToolReply -> {
                    val text = out.content
                    when {
                        out.groupId.isNotEmpty() ->
                            bot.getGroup(out.groupId.toLongOrNull() ?: return@MessageSink)
                                ?.sendMessage(text)
                        else -> {
                            val uid = out.userId.toLongOrNull() ?: return@MessageSink
                            val contact = bot.getFriend(uid) ?: bot.getStranger(uid)
                            contact?.sendMessage(text)
                        }
                    }
                }
                is OutboundMessage.Debug -> { println("DEBUG: ${out.content}") }
                is OutboundMessage.Think -> { println("THINK: ${out.content}") }
            }
        }

        override val authorizer = ToolAuthorizer { userId, toolName ->
            if (adminToolList.contains(toolName)) adminList.contains(userId) else true
        }
    }

    // endregion
}
