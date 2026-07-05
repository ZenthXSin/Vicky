package org.example.vicky.channel.onebot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import org.example.vicky.agent.Agent
import org.example.vicky.agent.AgentConfig
import org.example.vicky.command.CommandContext
import org.example.vicky.command.CommandRegistry
import org.example.vicky.agent.EmbeddingConfig
import org.example.vicky.context.ContextBuilder
import org.example.vicky.context.ContextCompactor
import org.example.vicky.context.ContextManager
import org.example.vicky.context.ConversationStore
import org.example.vicky.context.DefaultContextManager
import org.example.vicky.buffer.BufferedMessage
import org.example.vicky.buffer.MessageBuffer
import org.example.vicky.buffer.RichMediaItem
import org.example.vicky.file.FileIndexService
import org.example.vicky.generated.ToolRegistry
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.llm.EmbeddingClientFactory
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.memory.Distiller
import org.example.vicky.memory.DistillationScheduler
import org.example.vicky.memory.QdrantMemoryStore
import org.example.vicky.memory.RawMemory
import org.example.vicky.session.SessionStore
import org.example.vicky.session.SqliteSessionStore
import org.example.vicky.tool.ToolAuthorizer
import top.mrxiaom.overflow.BotBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 语义记忆扩展配置。
 */
data class MemoryConfig(
    val embedding: EmbeddingConfig? = null,
    val vectorStoreDataDir: String = "data/vector",
    val memoryEnabled: Boolean = false,
    val memoryTopK: Int = 5,
    val memoryTokenBudget: Int = 800,
    val memoryMaxPerUser: Int = 500,
    val memoryExpiryDays: Int = 90,
    val memoryRawRetentionDays: Int = 30,
    val memoryDistilledRetentionDays: Int = 7,
    val memoryCollection: String = "vicky_memories",
    val memoryRawCollection: String = "vicky_memories_raw",
    val distillationEnabled: Boolean = true,
    val distillationSchedule: String = "0 2 * * *",
    val distillationMaxConversations: Int = 10,
    val distillationTemperature: Double = 0.1,
    val distillationMaxTokens: Int = 1000,
    val fileIndexEnabled: Boolean = false,
    val fileIndexCollection: String = "vicky_files",
    val fileIndexChunkSize: Int = 500,
    val fileIndexChunkOverlap: Int = 50,
    val fileIndexIgnorePatterns: List<String> = listOf(".git", ".gradle", "build", "node_modules", "config/tmp"),
    val fileIndexPaths: List<String> = emptyList(),
    val fileIndexAutoIndexOnStart: Boolean = true,
)

class OneBot(
    var agentConfig: AgentConfig,
    var memoryConfig: MemoryConfig = MemoryConfig(),
    var url: String,
    var token: String,
) {
    val adminList = mutableSetOf<String>()
    val adminToolList = mutableSetOf<String>()
    val groupWhitelist = mutableSetOf<String>()
    val userWhitelist = mutableSetOf<String>()
    val commandRegistry = CommandRegistry()

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
        // Initialize MiraiToolImpl before agent creation (initTools needs it)
        MiraiToolImpl.bot = bot!!
        MiraiToolImpl.messageSourceCache = messageSourceCache
        MiraiToolImpl.groupWhitelist = groupWhitelist
        MiraiToolImpl.onGroupWhitelistChanged = {
            onGroupWhitelistChanged?.invoke(groupWhitelist.toSet())
        }
        // Register mirai L2 tool names as admin-gated
        listOf(
            "send_message", "group_manage", "friend_manage", "group_quit", "group_announcements",
            "file_write", "send_image", "send_video", "recall_message", "set_name_card",
            "essence_message", "group_files", "group_whitelist_add", "manage_tools", "manage_skills", "manage_scripts"
        ).forEach { adminToolList.add(it) }
        agent = OneBotAgent(agentConfig, memoryConfig, bot!!, buffer, adminList, adminToolList)
        // 注册脚本管理命令（必须在 agent 构造完成后，因为 initTools 在构造函数中调用时 commandRegistry 尚未就绪）
        val scriptsDir = java.io.File(org.example.vicky.config.ConfigManager.getConfigDir(), "scripts")
        org.example.vicky.command.ScriptCommands.all(scriptsDir, agent.tools)
            .forEach { commandRegistry.register(it) }
        commandRegistry.register(org.example.vicky.command.ContextInfoCommand.create(agent))
        registerListeners()
        return true
    }

    // region 事件监听

    /** 分配消息引用 ID 并缓存 MessageSource，超限时清理最旧的缓存条目。 */
    private fun cacheMessageRef(source: MessageSource): String {
        val ref = "#${msgCounter.incrementAndGet()}"
        messageSourceCache[ref] = source
        if (messageSourceCache.size > 2000) {
            val toRemove = messageSourceCache.keys.take(200)
            toRemove.forEach { messageSourceCache.remove(it) }
        }
        return ref
    }

    private fun registerListeners() {
        val b = bot ?: return
        val channel = b.eventChannel

        // 监听器A: 全量消息 (群+好友) → 拆分存入缓冲区
        // HIGHEST 优先于 NORMAL，确保缓冲先于触发。
        channel.subscribeAlways<GroupMessageEvent>(priority = EventPriority.HIGHEST) {
            val ref = cacheMessageRef(message.source)
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
            val ref = cacheMessageRef(message.source)
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
            val ref = cacheMessageRef(message.source)
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

        // 监听器B: 群消息 → 检测 @Bot → 命令分发 / 触发 Agent
        channel.subscribeAlways<GroupMessageEvent> {
            if (!isBotMentioned(message, b)) return@subscribeAlways
            if (group.id.toString() !in groupWhitelist && sender.id.toString() !in adminList) return@subscribeAlways

            val rawText = message.content
            val conversationId = group.id.toString()
            val userId = sender.id.toString()
            val groupId = group.id.toString()
            val isAdmin = userId in adminList

            // 剥离 @Bot mention 后再分发命令（群消息 rawText 含 @userId 前缀）
            val cmdText = rawText.replace(Regex("^@\\d+\\s*"), "")
            val cmdResult = commandRegistry.dispatch(
                CommandContext(userId, conversationId, groupId, buildContactSink(userId, groupId), isAdmin),
                cmdText,
            )
            if (cmdResult != null) {
                cmdResult.reply?.let { group.sendMessage(it) }
                if (!cmdResult.passthrough) return@subscribeAlways
            }

            val inbound = buildInboundFromBuffer(
                conversationId = conversationId,
                userId = userId,
                groupId = groupId,
                groupName = group.name,
                currentText = rawText,
                senderName = sender.nameCardOrNick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }

        // 监听器C: 好友消息 → 命令分发 / 触发 Agent
        channel.subscribeAlways<FriendMessageEvent> {
            if (sender.id.toString() !in userWhitelist && sender.id.toString() !in adminList) return@subscribeAlways

            val rawText = message.content
            val userId = sender.id.toString()
            val conversationId = userId
            val isAdmin = userId in adminList

            val cmdResult = commandRegistry.dispatch(
                CommandContext(userId, conversationId, sink = buildContactSink(userId, ""), isAdmin = isAdmin),
                rawText,
            )
            if (cmdResult != null) {
                val contact = bot.getFriend(sender.id) ?: bot.getStranger(sender.id)
                contact?.sendMessage(cmdResult.reply ?: return@subscribeAlways)
                if (!cmdResult.passthrough) return@subscribeAlways
            }

            val inbound = buildInboundFromBuffer(
                conversationId = conversationId,
                userId = userId,
                groupId = "",
                groupName = "",
                currentText = rawText,
                senderName = sender.nick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }

        // 监听器D: 陌生人/临时会话消息 → 命令分发 / 触发 Agent
        channel.subscribeAlways<StrangerMessageEvent> {
            if (sender.id.toString() !in userWhitelist && sender.id.toString() !in adminList) return@subscribeAlways

            val rawText = message.content
            val userId = sender.id.toString()
            val conversationId = userId
            val isAdmin = userId in adminList

            val cmdResult = commandRegistry.dispatch(
                CommandContext(userId, conversationId, sink = buildContactSink(userId, ""), isAdmin = isAdmin),
                rawText,
            )
            if (cmdResult != null) {
                val contact = bot.getFriend(sender.id) ?: bot.getStranger(sender.id)
                contact?.sendMessage(cmdResult.reply ?: return@subscribeAlways)
                if (!cmdResult.passthrough) return@subscribeAlways
            }

            val inbound = buildInboundFromBuffer(
                conversationId = conversationId,
                userId = userId,
                groupId = "",
                groupName = "",
                currentText = rawText,
                senderName = sender.nick,
            )
            println("[→Agent] $inbound")
            agent.receive(inbound)
        }
    }

    // endregion

    // region 命令 sink

    /** 构建一个 MessageSink，将消息路由回对应用户/群。 */
    private fun buildContactSink(userId: String, groupId: String): MessageSink = MessageSink { out ->
        val text = out.content
        when {
            groupId.isNotEmpty() -> bot?.getGroup(groupId.toLongOrNull() ?: return@MessageSink)?.sendMessage(text)
            else -> {
                val uid = userId.toLongOrNull() ?: return@MessageSink
                val contact = bot?.getFriend(uid) ?: bot?.getStranger(uid)
                contact?.sendMessage(text)
            }
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
        private val memConfig: MemoryConfig,
        private val bot: Bot,
        override val buffer: MessageBuffer,
        private val adminList: Set<String>,
        private val adminToolList: Set<String>,
    ) : Agent(config) {

        override val sessionStore: SessionStore = SqliteSessionStore(
            java.io.File(org.example.vicky.config.ConfigManager.getConfigDir(), "data")
        )

        override val contextManager: ContextManager = DefaultContextManager(
            store = ConversationStore(
                maxConversations = config.conversationStoreMaxConversations,
                maxMessages = config.conversationStoreMaxMessages,
            ),
            builder = ContextBuilder(config.agentMd),
            compactor = ContextCompactor(config, OpenAiClientFactory.create(config)),
        )

        private var toolManagementTool: org.example.vicky.tool.builtin.ToolManagementTool? = null
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // 语义记忆组件
        private var vectorStore: org.example.vicky.vector.VectorStore? = null
        private var memoryStore: QdrantMemoryStore? = null
        private var fileIndexService: FileIndexService? = null
        private var distillationScheduler: DistillationScheduler? = null
        private val memoryCircuitBreaker = org.example.vicky.vector.CircuitBreaker(failureThreshold = 3, cooldownMs = 120_000)
        @Volatile private var memoryReady = false

        override fun initMemory() {
            val embeddingClient = memConfig.embedding?.let { EmbeddingClientFactory.create(it) } ?: return
            val dimText = if (embeddingClient.dimension > 0) "维度: ${embeddingClient.dimension}" else "维度: 待首次调用确定"
            println("[Vicky] 语义模型已加载（$dimText）")

            val dataDir = java.io.File(memConfig.vectorStoreDataDir)
            vectorStore = org.example.vicky.vector.JVectorStore(dataDir)
            println("[Vicky] 向量存储已初始化 (JVector): ${dataDir.absolutePath}")

            memoryStore = QdrantMemoryStore(
                vectorStore!!, embeddingClient,
                memConfig.memoryCollection, memConfig.memoryRawCollection,
                memConfig.memoryRawRetentionDays.toLong(),
                memConfig.memoryDistilledRetentionDays.toLong(),
                memConfig.memoryExpiryDays.toLong(),
            )

            if (memConfig.fileIndexEnabled) {
                fileIndexService = FileIndexService(
                    vectorStore!!, embeddingClient,
                    java.io.File(System.getProperty("user.dir")),
                    memConfig.fileIndexCollection,
                    memConfig.fileIndexChunkSize,
                    memConfig.fileIndexChunkOverlap,
                    memConfig.fileIndexIgnorePatterns,
                    memConfig.fileIndexPaths,
                )
            }

            if (memConfig.distillationEnabled) {
                val openAi = org.example.vicky.llm.OpenAiClientFactory.create(config)
                val distiller = Distiller(openAi, embeddingClient, config.model)
                distillationScheduler = DistillationScheduler(memoryStore!!, distiller, memConfig.distillationMaxConversations, true)
            }

            println("[Vicky] 记忆系统后台初始化中，完成前记忆功能禁用...")
            scope.launch(Dispatchers.IO) {
                try {
                    embeddingClient.embed("warmup")
                    memoryReady = true
                    println("[Vicky] 记忆系统已就绪（topK: ${memConfig.memoryTopK}, tokenBudget: ${memConfig.memoryTokenBudget}）")
                    distillationScheduler?.also { it.start(); println("[Vicky] 蒸馏调度器已启动") }
                    if (fileIndexService != null && memConfig.fileIndexAutoIndexOnStart) {
                        println("[Vicky] 开始后台索引文件...")
                        try {
                            val result = fileIndexService!!.indexAll { current, success, skipped ->
                                print("\r[Vicky] 索引中: 已处理 $current 个文件，$success 个已索引，$skipped 个已跳过")
                            }
                            println("\n[Vicky] 文件索引完成: ${result.newFiles} 个新增，${result.updatedFiles} 个更新，${result.skippedFiles} 个跳过")
                        } catch (e: Exception) {
                            println("\n[Vicky] 文件索引失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    println("[Vicky] 记忆系统初始化失败: ${e.message}")
                }
            }
        }

        override fun initTools() {
            super.initTools()
            if (config.builtinTools) {
                val baseDir = java.io.File(System.getProperty("user.dir"))
                org.example.vicky.tool.builtin.BuiltinTools.all(baseDir, memoryStore, fileIndexService, distillationScheduler, agentConfig = config)
                    .forEach { tools.register(it) }
                toolManagementTool = org.example.vicky.tool.builtin.ToolManagementTool { onToolStatesChanged() }
                tools.register(toolManagementTool!!)
                tools.register(org.example.vicky.tool.builtin.InvokeSkillTool())
                tools.register(org.example.vicky.tool.builtin.ManageSkillsTool { onSkillStatesChanged() })
                for ((toolName, enabled) in config.toolStates) {
                    if (!enabled) tools.unregister(toolName)
                }
            }
            ToolRegistry.tools("mirai").forEach { tools.register(it) }

            // 脚本管理工具 + 自动加载所有 .ts 脚本
            val scriptsDir = java.io.File(org.example.vicky.config.ConfigManager.getConfigDir(), "scripts")
            tools.register(org.example.vicky.tool.builtin.ManageScriptsTool(scriptsDir, tools))

            // 自动加载 scripts 目录下所有 .ts 文件
            if (scriptsDir.exists()) {
                val tsFiles = scriptsDir.listFiles { f -> f.isFile && f.extension == "ts" }
                if (tsFiles != null) {
                    for (file in tsFiles) {
                        try {
                            org.example.vicky.script.ScriptManager.loadAndRegister(file, tools)
                        } catch (e: Exception) {
                            println("[Vicky][script] 自动加载失败: ${file.name}: ${e.message}")
                        }
                    }
                    org.example.vicky.script.ScriptManager.logStats()
                }
            }

        }

        override suspend fun onMessageEmitted(out: OutboundMessage) {
            when (out) {
                is OutboundMessage.AgentReply -> buffer.store(
                    out.conversationId,
                    BufferedMessage(text = out.content, richMedia = emptyList(), raw = out.content, userId = config.id, senderName = name ?: config.id),
                )
                is OutboundMessage.ToolReply -> buffer.store(
                    out.conversationId,
                    BufferedMessage(text = out.content, richMedia = emptyList(), raw = out.content, userId = config.id, senderName = name ?: config.id, msgRef = out.toolName),
                )
                else -> {}
            }
        }

        override suspend fun onTurnStart(msg: InboundMessage, history: MutableList<ChatMessage>) {
            if (memoryStore == null || !memConfig.memoryEnabled || !memoryReady) return
            if (!memoryCircuitBreaker.allowRequest()) return
            try {
                val memories = kotlinx.coroutines.withTimeout(30_000) {
                    memoryStore!!.recall(msg.content, msg.userId, memConfig.memoryTopK)
                }
                memoryCircuitBreaker.recordSuccess()
                if (memories.isNotEmpty()) {
                    var budget = memConfig.memoryTokenBudget
                    val memSb = StringBuilder()
                    memSb.appendLine("# Memory")
                    for (memory in memories) {
                        if (budget <= 0) break
                        val line = "- [${memory.source}] ${memory.content}"
                        val truncated = if (line.length > budget) line.take(budget) + "…" else line
                        memSb.appendLine(truncated)
                        budget -= truncated.length
                    }
                    val memoryText = memSb.toString().trimEnd()
                    if (memoryText.contains("[")) {
                        history.add(1, ChatMessage(role = ChatRole.System, content = memoryText))
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                memoryCircuitBreaker.recordFailure()
                println("[Vicky] 记忆读取超时 (5s)，跳过")
            } catch (e: Exception) {
                memoryCircuitBreaker.recordFailure()
                println("[Vicky] 记忆读取失败: ${e.message}")
            }
        }

        override suspend fun onTurnEnd(msg: InboundMessage, assistantReply: String?) {
            if (memoryStore == null || !memConfig.memoryEnabled || !memoryReady) return
            if (!memoryCircuitBreaker.allowRequest()) return
            try {
                kotlinx.coroutines.withTimeout(30_000) {
                    val rawMemories = mutableListOf<RawMemory>()
                    rawMemories.add(RawMemory(userId = msg.userId, conversationId = msg.conversationId, role = "user", content = msg.content))
                    if (assistantReply != null) {
                        rawMemories.add(RawMemory(userId = msg.userId, conversationId = msg.conversationId, role = "assistant", content = assistantReply))
                    }
                    for (raw in rawMemories) {
                        memoryStore!!.rememberRaw(raw)
                    }
                }
                memoryCircuitBreaker.recordSuccess()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                memoryCircuitBreaker.recordFailure()
                println("[Vicky] 记忆保存超时 (5s)，跳过")
            } catch (e: Exception) {
                memoryCircuitBreaker.recordFailure()
                println("[Vicky] 原始记忆保存失败 (${e::class.simpleName}): ${e.message}")
                if (config.debug) e.printStackTrace()
            }
        }

        override fun onToolStatesChanged() {
            val activeStates = tools.snapshot().filter { it.name != "manage_tools" }.map { it.name to true }
            val disabledStates = toolManagementTool?.getDisabledToolNames().orEmpty().map { it to false }
            val allStates = (activeStates + disabledStates).toMap()
            val configData = org.example.vicky.config.ConfigManager.loadOrCreate().config
            org.example.vicky.config.ConfigManager.save(configData.copy(toolStates = allStates))
        }

        override fun onSkillStatesChanged() {
            val states = org.example.vicky.skill.SkillManager.getStates()
            val configData = org.example.vicky.config.ConfigManager.loadOrCreate().config
            org.example.vicky.config.ConfigManager.save(configData.copy(skillStates = states))
        }

        override fun close() {
            super.close()
            distillationScheduler?.stop()
            scope.cancel()
            vectorStore?.close()
        }

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
                is OutboundMessage.TokenUsage -> { println("[llm] ${out.content}") }
            }
        }

        override val authorizer = ToolAuthorizer { userId, toolName ->
            if (adminToolList.contains(toolName)) adminList.contains(userId) else true
        }
    }

    // endregion
}
