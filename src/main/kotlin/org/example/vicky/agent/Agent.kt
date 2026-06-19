package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolType
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.example.vicky.context.ContextBuilder
import org.example.vicky.context.ContextCompactor
import org.example.vicky.context.ConversationStore
import org.example.vicky.file.FileIndexService
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.llm.EmbeddingClientFactory
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.memory.Distiller
import org.example.vicky.memory.DistillationScheduler
import org.example.vicky.memory.QdrantMemoryStore
import org.example.vicky.memory.RawMemory
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import org.example.vicky.tool.builtin.BuiltinTools
import org.example.vicky.vector.QdrantVectorStore
import com.aallam.openai.api.chat.Tool as OAITool

/**
 * Agent 抽象基类。子类负责提供消息出口 ([sink]) 与权限策略 ([authorizer])，
 * 并通过 [receive] 把入站消息送进来。
 */
abstract class Agent(
    protected val config: AgentConfig,
    private val openAi: OpenAI = OpenAiClientFactory.create(config),
    protected val contextBuilder: ContextBuilder = ContextBuilder(config.agentMd),
    protected val store: ConversationStore = ConversationStore(),
) {
    val tools: ToolRegistry = ToolRegistry()
    private val compactor = ContextCompactor(config, openAi)

    // 向量存储组件（Qdrant 不可用时自动禁用记忆功能）
    private val vectorStore: QdrantVectorStore? = run {
        if (config.qdrantHost == null || config.embedding == null) return@run null
        try {
            QdrantVectorStore(config.qdrantHost, config.qdrantHttpPort)
        } catch (e: Exception) {
            println("[Vicky] Qdrant 连接失败: ${e.message}")
            println("[Vicky] 记忆功能已禁用")
            null
        }
    }

    private val embeddingClient: org.example.vicky.llm.EmbeddingClient? = run {
        config.embedding?.let { EmbeddingClientFactory.create(it) }
    }

    private val memoryStore: QdrantMemoryStore? = if (vectorStore != null && embeddingClient != null) {
        QdrantMemoryStore(vectorStore, embeddingClient, config.memoryCollection, config.memoryRawCollection)
    } else null

    private val fileIndexService: FileIndexService? = if (vectorStore != null && embeddingClient != null && config.fileIndexEnabled) {
        FileIndexService(
            vectorStore, embeddingClient,
            java.io.File(System.getProperty("user.dir")),
            config.fileIndexCollection,
            config.fileIndexChunkSize,
            config.fileIndexChunkOverlap,
            config.fileIndexIgnorePatterns,
            config.fileIndexPaths,
        )
    } else null

    private val distiller: Distiller? = if (memoryStore != null && embeddingClient != null) {
        Distiller(openAi, embeddingClient)
    } else null

    private val distillationScheduler: DistillationScheduler? = if (distiller != null && config.distillationEnabled) {
        DistillationScheduler(memoryStore!!, distiller, config.distillationMaxConversations, true)
    } else null

    init {
        // 打印向量存储初始化状态
        if (embeddingClient != null) {
            val dimText = if (embeddingClient.dimension > 0) "维度: ${embeddingClient.dimension}" else "维度: 待首次调用确定"
            println("[Vicky] 语义模型已加载（$dimText）")
        }
        if (vectorStore != null) {
            println("[Vicky] 向量存储已连接: ${config.qdrantHost}:${config.qdrantHttpPort}")
        }
        if (memoryStore != null) {
            println("[Vicky] 记忆系统已启用（topK: ${config.memoryTopK}, tokenBudget: ${config.memoryTokenBudget}）")
        }

        if (config.builtinTools) {
            val baseDir = java.io.File(System.getProperty("user.dir"))
            BuiltinTools.all(baseDir, memoryStore, fileIndexService, distillationScheduler).forEach { tools.register(it) }
        }

        // 启动时后台自动索引文件（不阻塞主线程）
        if (fileIndexService != null && config.fileIndexAutoIndexOnStart) {
            println("[Vicky] 开始后台索引文件...")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = fileIndexService.indexAll { current, success, skipped ->
                        print("\r[Vicky] 索引中: 已处理 $current 个文件，$success 个已索引，$skipped 个已跳过")
                    }
                    println("\n[Vicky] 文件索引完成: ${result.newFiles} 个新增，${result.updatedFiles} 个更新，${result.skippedFiles} 个跳过")
                } catch (e: Exception) {
                    println("\n[Vicky] 文件索引失败: ${e.message}")
                }
            }
        }

        // 启动蒸馏调度器
        if (distillationScheduler != null) {
            distillationScheduler.start()
            println("[Vicky] 蒸馏调度器已启动")
        }
    }

    /** 子类提供：消息出口。 */
    protected abstract val sink: MessageSink

    /** 子类提供：工具权限。默认全允许可在子类中 override。 */
    protected open val authorizer: ToolAuthorizer = ToolAuthorizer.ALLOW_ALL

    /** 子类提供：消息缓冲区。控制台等无缓冲场景保持 null。 */
    protected open val buffer: org.example.vicky.channel.onebot.MessageBuffer? = null

    /** 运行时注册工具。 */
    fun registerTool(tool: Tool) = tools.register(tool)
    fun unregisterTool(name: String): Tool? = tools.unregister(name)

    /**
     * 入口：外部把收到的消息丢进来。
     */
    suspend fun receive(
        msg: InboundMessage,
        replySink: MessageSink? = null,
        clearContextAfter: Boolean = false,
    ) {
        val history = store.history(msg.conversationId)
        ensureSystemPrompt(history)

        // 记忆 recall
        if (memoryStore != null && config.memoryEnabled) {
            try {
                val memories = memoryStore.recall(msg.content, msg.userId, config.memoryTopK)
                if (memories.isNotEmpty()) {
                    var budget = config.memoryTokenBudget
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
            } catch (e: Exception) {
                println("[Vicky] 记忆读取失败: ${e.message}")
            }
        }

        history += ChatMessage(role = ChatRole.User, content = msg.content)

        suspend fun emit(out: OutboundMessage) {
            sink.emit(out)
            replySink?.emit(out)
        }

        suspend fun log(message: String) {
            if (config.debug) emit(OutboundMessage.Debug(msg.conversationId, msg.userId, msg.groupId, message))
        }

        suspend fun logThink(content: String) {
            if (config.think) emit(OutboundMessage.Think(msg.conversationId, msg.userId, msg.groupId, content))
        }

        val oaiTools = if (config.mode.toolsEnabled) buildOpenAiTools() else emptyList()
        compactor.ensureContextBudget(history)

        var assistantReply: String? = null

        try {
            repeat(config.maxSteps) { step ->
                log("step ${step + 1}/${config.maxSteps} -> requesting completion (${history.size} msgs)")
                val request = ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    tools = oaiTools.takeIf { it.isNotEmpty() },
                    temperature = config.temperature,
                )
                val assistant = openAi.chatCompletion(request).choices.first().message
                history += assistant

                val calls = assistant.toolCalls.orEmpty()
                if (calls.isEmpty()) {
                    log("step ${step + 1}: no tool calls, finishing")
                    assistantReply = assistant.content
                    if (config.mode.emitAgentText) {
                        assistant.content?.takeIf { it.isNotBlank() }?.let {
                            emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, msg.groupId, it))
                        }
                    }
                    return
                }

                assistant.content?.takeIf { it.isNotBlank() }?.let { logThink(it) }

                var endTurn = false
                for (call in calls) {
                    if (call !is ToolCall.Function) continue
                    val toolName = call.function.name
                    logThink("Use Tool: $toolName")
                    log("step ${step + 1}: invoking tool '$toolName'")
                    val result = invokeTool(msg, toolName, call.function.argumentsAsJson())
                    history += ChatMessage(
                        role = ChatRole.Tool,
                        toolCallId = call.id,
                        name = toolName,
                        content = result.toAgent,
                    )
                    result.userReply?.takeIf { it.isNotBlank() }?.let {
                        emit(OutboundMessage.ToolReply(msg.conversationId, msg.userId, msg.groupId, it, toolName))
                    }
                    if (result.endTurn) endTurn = true
                }
                compactOldToolRounds(history)
                compactor.ensureContextBudget(history)
                if (endTurn) {
                    log("step ${step + 1}: endTurn signaled, finishing turn")
                    return
                }
            }
            // 步数耗尽
            log("reached maxSteps (${config.maxSteps}); requesting a wrap-up summary")
            history += ChatMessage(
                role = ChatRole.User,
                content = "System notice: the step budget for this turn is exhausted; no more tools can be " +
                    "called. Based on the information gathered so far, summarize the current situation and " +
                    "report your conclusion to the user, and explicitly note that some actions may be " +
                    "incomplete because the step limit was reached. Reply in the user's language.",
            )
            val wrapUp = openAi.chatCompletion(
                ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    temperature = config.temperature,
                )
            ).choices.first().message
            history += wrapUp
            assistantReply = wrapUp.content
            wrapUp.content?.takeIf { it.isNotBlank() }?.let {
                emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, msg.groupId, it))
            }
        } finally {
            // 无论如何都保存原始记忆
            if (memoryStore != null && config.memoryEnabled) {
                try {
                    val rawMemories = mutableListOf<RawMemory>()
                    rawMemories.add(
                        RawMemory(
                            userId = msg.userId,
                            conversationId = msg.conversationId,
                            role = "user",
                            content = msg.content,
                        )
                    )
                    if (assistantReply != null) {
                        rawMemories.add(
                            RawMemory(
                                userId = msg.userId,
                                conversationId = msg.conversationId,
                                role = "assistant",
                                content = assistantReply!!,
                            )
                        )
                    }
                    for (raw in rawMemories) {
                        memoryStore.rememberRaw(raw)
                    }
                } catch (e: Exception) {
                    println("[Vicky] 原始记忆保存失败: ${e.message}")
                }
            }

            // 清理上下文
            if (clearContextAfter) {
                store.clear(msg.conversationId)
                log("cleared context for '${msg.conversationId}' after reply")
            }
        }
    }

    private suspend fun invokeTool(
        msg: InboundMessage,
        toolName: String,
        args: kotlinx.serialization.json.JsonObject,
    ): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult(toAgent = "Error: tool '$toolName' not found.")
        if (!authorizer.allow(msg.userId, toolName)) {
            return ToolResult(toAgent = "Error: permission denied for user '${msg.userId}' on tool '$toolName'.")
        }
        val ctx = ToolContext(msg.userId, msg.conversationId, msg.groupId, store, tools, buffer)
        return runCatching { tool.execute(ctx, args) }
            .getOrElse { ToolResult(toAgent = "Error executing '$toolName': ${it.message}") }
    }

    private fun compactOldToolRounds(history: MutableList<ChatMessage>) {
        val roundStarts = history.withIndex()
            .filter { (_, m) -> m.role == ChatRole.Assistant && !m.toolCalls.isNullOrEmpty() }
            .map { it.index }
        if (roundStarts.size <= KEEP_RECENT_TOOL_ROUNDS) return
        val cutoff = roundStarts[roundStarts.size - KEEP_RECENT_TOOL_ROUNDS]
        for (i in 0 until cutoff) {
            val m = history[i]
            if (m.role != ChatRole.Tool) continue
            val original = m.content ?: continue
            if (original.startsWith(SUMMARY_PREFIX)) continue
            val status = if (original.trimStart().startsWith("Error")) "failed" else "ok"
            history[i] = ChatMessage(
                role = ChatRole.Tool,
                toolCallId = m.toolCallId,
                name = m.name,
                content = "$SUMMARY_PREFIX${m.name ?: "tool"}: $status",
            )
        }
    }

    private fun ensureSystemPrompt(history: MutableList<ChatMessage>) {
        val prompt = contextBuilder.systemPrompt(config.mode, tools)
        if (history.isEmpty() || history.first().role != ChatRole.System) {
            history.add(0, ChatMessage(role = ChatRole.System, content = prompt))
        } else {
            history[0] = ChatMessage(role = ChatRole.System, content = prompt)
        }
    }

    private fun buildOpenAiTools(): List<OAITool> = tools.snapshot().map { t ->
        OAITool(
            type = ToolType.Function,
            function = FunctionTool(
                name = t.name,
                description = t.description,
                parameters = Parameters.fromJsonString(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), t.parameters)),
            ),
        )
    }

    private companion object {
        const val KEEP_RECENT_TOOL_ROUNDS = 2
        const val SUMMARY_PREFIX = "[summary] "
    }
}
