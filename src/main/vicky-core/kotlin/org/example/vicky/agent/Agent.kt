package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolType
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.client.OpenAI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.io.estimatedContentChars
import org.example.vicky.io.textContentOrNull
import org.example.vicky.io.toChatMessage
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.session.Session
import org.example.vicky.session.SessionStore
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import java.util.concurrent.ConcurrentHashMap
import com.aallam.openai.api.chat.Tool as OAITool

/**
 * Agent 抽象基类。子类负责提供消息出口 ([sink]) 与权限策略 ([authorizer])，
 * 并通过 [receive] 把入站消息送进来。
 */
abstract class Agent(
    protected val config: AgentConfig,
    private val openAi: OpenAI = OpenAiClientFactory.create(config),
) : AutoCloseable {
    protected abstract val contextManager: ContextManager
    val tools: ToolRegistry = ToolRegistry()
    val id: String get() = config.id
    val name: String? get() = config.name
    private var cachedOaiTools: List<OAITool>? = null

    open val sessionStore: SessionStore? = null

    private var memoryInitialized = false
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var lastSessionCleanupAt: Long = 0L

    init {
        AgentManager.register(this)
        initTools()
    }

    /** 子类提供：消息出口。 */
    protected abstract val sink: MessageSink

    /** 子类提供：工具权限。默认全允许可在子类中 override。 */
    protected open val authorizer: ToolAuthorizer = ToolAuthorizer.ALLOW_ALL

    /** 子类提供：消息缓冲区。控制台等无缓冲场景保持 null。类型由子类决定。 */
    protected open val buffer: Any? = null

    /** 子类 override 以初始化记忆系统（向量存储、Embedding 等）。在 initTools() 之前调用。 */
    protected open fun initMemory() {}

    fun triggerInit() {
        if (!memoryInitialized) {
            memoryInitialized = true
            initMemory()
        }
    }

    /** 子类 override 以注册内置工具。默认无操作。 */
    protected open fun initTools() {}

    /** 子类 override 以持久化工具状态。默认无操作。 */
    protected open fun onToolStatesChanged() {}

    /** 子类 override 以持久化技能状态。默认无操作。 */
    protected open fun onSkillStatesChanged() {}

    /** 子类 override 以在每轮结束时保存原始记忆等。默认无操作。 */
    protected open suspend fun onTurnEnd(msg: InboundMessage, assistantReply: String?) {}

    /** 子类 override 以在每轮开始时 recall 记忆并注入 history。默认无操作。 */
    protected open suspend fun onTurnStart(msg: InboundMessage, history: MutableList<ChatMessage>) {}

    /** 子类 override 以在消息发出后同步存储到消息缓冲区等。默认无操作。 */
    protected open suspend fun onMessageEmitted(out: OutboundMessage) {}

    /** 运行时注册工具。 */
    fun registerTool(tool: Tool) {
        tools.register(tool)
        cachedOaiTools = null
    }

    fun unregisterTool(name: String): Tool? {
        val result = tools.unregister(name)
        if (result != null) cachedOaiTools = null
        return result
    }

    fun session(conversationId: String): Session {
        val now = System.currentTimeMillis()
        cleanupIdleSessions(now)
        return sessions.getOrPut(conversationId) { Session(conversationId, sessionStore, this) }
            .also { it.touch(now) }
    }

    suspend fun clearContext(conversationId: String) {
        contextManager.clear(conversationId)
        sessions.remove(conversationId)?.delete() ?: sessionStore?.deleteHistory(conversationId)
    }

    /**
     * 入口：外部把收到的消息丢进来。
     * 同一 conversationId 串行执行，避免并发修改 history。
     */
    suspend fun receive(
        msg: InboundMessage,
        replySink: MessageSink? = null,
        clearContextAfter: Boolean = false,
    ) = session(msg.conversationId).receive(msg, replySink, clearContextAfter)

    internal suspend fun receiveInternal(
        msg: InboundMessage,
        replySink: MessageSink?,
        clearContextAfter: Boolean,
    ) {
        if (!memoryInitialized) {
            memoryInitialized = true
            initMemory()
        }
        val history = contextManager.history(msg.conversationId)

        // 从持久化存储恢复历史（首次访问）
        val sess = session(msg.conversationId)
        if (!sess.historyLoaded) {
            sess.historyLoaded = true
            val persisted = sess.loadHistory()
            if (persisted.isNotEmpty() && history.isEmpty()) {
                history.addAll(persisted)
            }
        }

        // 确保 system prompt 存在且为最新
        val prompt = contextManager.buildSystemPrompt(config.mode, tools)
        if (history.isEmpty() || history.first().role != ChatRole.System) {
            history.add(0, ChatMessage(role = ChatRole.System, content = prompt))
        } else {
            history[0] = ChatMessage(role = ChatRole.System, content = prompt)
        }

        // 子类 recall 记忆注入
        onTurnStart(msg, history)

        history += msg.toChatMessage()

        suspend fun emit(out: OutboundMessage) {
            sink.emit(out)
            replySink?.emit(out)
            onMessageEmitted(out)
        }

        suspend fun log(message: String) {
            if (config.debug) emit(OutboundMessage.Debug(msg.conversationId, msg.userId, msg.groupId, message))
        }

        suspend fun logThink(content: String) {
            if (config.think) emit(OutboundMessage.Think(msg.conversationId, msg.userId, msg.groupId, content))
        }

        val toolsEnabled = config.mode.toolsEnabled
        val oaiTools = if (toolsEnabled) buildOpenAiTools() else emptyList()
        val oaiToolsMinimal = if (toolsEnabled && config.lazyToolSchema) buildOpenAiToolsMinimal() else oaiTools
        contextManager.ensureContextBudget(history)

        var assistantReply: String? = null
        // 记录 wrap-up 注入的假消息，finally 里清理
        var wrapUpMessage: ChatMessage? = null

        try {
            repeat(config.maxSteps) { step ->
                val ctxEstimate = history.sumOf { it.estimatedContentChars() } / 3
                log("step ${step + 1}/${config.maxSteps} -> requesting completion (${history.size} msgs, ~${ctxEstimate}tk in)")
                val request = ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    tools = oaiToolsMinimal.takeIf { it.isNotEmpty() },
                    temperature = config.temperature,
                )
                var streamedReply = false
                val cr = ChatCompletionRunner(openAi, config).complete(
                    request,
                    onDebug = if (config.debug) { s -> log(s) } else null,
                    onDelta = { delta ->
                        streamedReply = true
                        emit(OutboundMessage.AgentReplyDelta(msg.conversationId, msg.userId, msg.groupId, delta))
                    },
                )
                sess.contextTokens += cr.promptTokens
                sess.usedTokens += cr.completionTokens
                emit(OutboundMessage.TokenUsage(msg.conversationId, msg.userId, msg.groupId, cr.promptTokens, cr.completionTokens, sess.usedTokens))

                // 两阶段：若 lazyToolSchema 且模型发出工具调用，用完整 schema 再请求一次以获取正确参数
                val assistant = if (config.lazyToolSchema && toolsEnabled && cr.message.toolCalls.orEmpty().isNotEmpty()) {
                    val calledNames = cr.message.toolCalls!!.mapNotNull { (it as? ToolCall.Function)?.function?.name }.toSet()
                    log("step ${step + 1}: lazy schema phase-2 for tools: $calledNames")
                    val fullSchemaTools = buildOpenAiToolsFor(calledNames)
                    val req2 = ChatCompletionRequest(
                        model = config.model,
                        messages = history.toList(),
                        tools = fullSchemaTools.takeIf { it.isNotEmpty() },
                        temperature = config.temperature,
                    )
                    streamedReply = false
                    val cr2 = ChatCompletionRunner(openAi, config).complete(
                        req2,
                        onDebug = if (config.debug) { s -> log(s) } else null,
                        onDelta = { delta ->
                            streamedReply = true
                            emit(OutboundMessage.AgentReplyDelta(msg.conversationId, msg.userId, msg.groupId, delta))
                        },
                    )
                    sess.contextTokens += cr2.promptTokens
                    sess.usedTokens += cr2.completionTokens
                    emit(OutboundMessage.TokenUsage(msg.conversationId, msg.userId, msg.groupId, cr2.promptTokens, cr2.completionTokens, sess.usedTokens))
                    cr2.message
                } else {
                    cr.message
                }

                history += assistant

                var calls = assistant.toolCalls.orEmpty()
                // 兜底：模型把工具调用输出在 content 里（如 DeepSeek DSML 格式）
                if (calls.isEmpty()) {
                    val parsed = InlineToolCallParser.parse(assistant.content.orEmpty())
                    if (parsed.isNotEmpty()) {
                        calls = parsed
                        log("step ${step + 1}: parsed ${parsed.size} inline tool calls from content")
                    }
                }
                if (calls.isEmpty()) {
                    log("step ${step + 1}: no tool calls, finishing")
                    assistantReply = assistant.content
                    if (config.mode.emitAgentText) {
                        val content = assistant.content.orEmpty()
                        if (streamedReply) {
                            emit(OutboundMessage.AgentReplyDone(msg.conversationId, msg.userId, msg.groupId))
                        } else {
                            content.takeIf { it.isNotBlank() }?.let {
                                emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, msg.groupId, it))
                            }
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
                    val rawArgs = call.function.argumentsOrNull.orEmpty()
                    val parsedArgs = runCatching { call.function.argumentsAsJson() }
                        .getOrElse { e ->
                            log("step ${step + 1}: arguments parse failed for '$toolName' (raw=${rawArgs.take(200)}): ${e.message}")
                            kotlinx.serialization.json.JsonObject(emptyMap())
                        }
                    val result = runCatching { invokeTool(msg, toolName, parsedArgs) }
                        .getOrElse { e ->
                            log("step ${step + 1}: tool '$toolName' threw: ${e.message}")
                            ToolResult(toAgent = "Error executing '$toolName': ${e.message}")
                        }
                    val toolContent = result.toAgent.let {
                        if (it.length > MAX_TOOL_RESULT_CHARS) {
                            log("step ${step + 1}: tool '$toolName' result truncated (${it.length} -> $MAX_TOOL_RESULT_CHARS chars)")
                            it.take(MAX_TOOL_RESULT_CHARS) + "\n...(truncated)"
                        } else it
                    }
                    history += ChatMessage(
                        role = ChatRole.Tool,
                        toolCallId = call.id,
                        name = toolName,
                        content = toolContent,
                    )
                    result.userReply?.takeIf { it.isNotBlank() }?.let {
                        emit(OutboundMessage.ToolReply(msg.conversationId, msg.userId, msg.groupId, it, toolName))
                    }
                    if (result.endTurn) endTurn = true
                }
                contextManager.ensureContextBudget(history)
                if (endTurn) {
                    log("step ${step + 1}: endTurn signaled, finishing turn")
                    return
                }
            }
            // 步数耗尽
            log("reached maxSteps (${config.maxSteps}); requesting a wrap-up summary")
            val wrapUpPrompt = ChatMessage(
                role = ChatRole.System,
                content = "System notice: the step budget for this turn is exhausted; no more tools can be " +
                    "called. Based on the information gathered so far, summarize the current situation and " +
                    "report your conclusion to the user, and explicitly note that some actions may be " +
                    "incomplete because the step limit was reached. Reply in the user's language.",
            )
            history += wrapUpPrompt
            wrapUpMessage = wrapUpPrompt
            contextManager.ensureContextBudget(history)
            var wrapUpStreamedReply = false
            val wrapUpCr = ChatCompletionRunner(openAi, config).complete(
                ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    temperature = config.temperature,
                ),
                onDebug = if (config.debug) { s -> log(s) } else null,
                onDelta = { delta ->
                    wrapUpStreamedReply = true
                    emit(OutboundMessage.AgentReplyDelta(msg.conversationId, msg.userId, msg.groupId, delta))
                },
            )
            sess.contextTokens += wrapUpCr.promptTokens
            sess.usedTokens += wrapUpCr.completionTokens
            emit(OutboundMessage.TokenUsage(msg.conversationId, msg.userId, msg.groupId, wrapUpCr.promptTokens, wrapUpCr.completionTokens, sess.usedTokens))
            val wrapUp = wrapUpCr.message
            history += wrapUp
            val wrapUpContent = wrapUp.content?.takeIf { it.isNotBlank() }
                ?: "[已达到最大步数限制，无法生成完整回复。]"
            assistantReply = wrapUpContent
            if (wrapUpStreamedReply) {
                emit(OutboundMessage.AgentReplyDone(msg.conversationId, msg.userId, msg.groupId))
            } else {
                emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, msg.groupId, wrapUpContent))
            }
        } catch (e: Throwable) {
            log("receive() failed: ${e::class.simpleName}: ${e.message}")
            if (config.debug) e.printStackTrace()
            runCatching {
                emit(
                    OutboundMessage.AgentReply(
                        msg.conversationId, msg.userId, msg.groupId,
                        "[内部错误] ${e::class.simpleName}: ${e.message ?: "unknown"}",
                    )
                )
            }
        } finally {
            // 清理 wrap-up 注入的假消息，不污染后续上下文
            wrapUpMessage?.let { history.remove(it) }

            // 任务结束后压缩已完成的 tool call 轮次
            contextManager.compactOldToolRounds(history)

            onTurnEnd(msg, assistantReply)

            if (clearContextAfter) {
                contextManager.clear(msg.conversationId)
                sess.delete()
                sessions.remove(msg.conversationId)
                log("cleared context for '${msg.conversationId}' after reply")
            } else {
                contextManager.trimIfNeeded(msg.conversationId)
                sess.flush(history)
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
        val ctx = ToolContext(msg.userId, msg.conversationId, msg.groupId, contextManager, tools, buffer)
        return runCatching { tool.execute(ctx, args) }
            .getOrElse { ToolResult(toAgent = "Error executing '$toolName': ${it.message}") }
    }

    private fun buildOpenAiTools(): List<OAITool> {
        cachedOaiTools?.let { return it }
        val result = OpenAiToolSchemaBuilder.build(tools)
        cachedOaiTools = result
        return result
    }

    private fun buildOpenAiToolsMinimal(): List<OAITool> =
        tools.snapshot().map { t ->
            OAITool(
                type = ToolType.Function,
                function = FunctionTool(
                    name = t.name,
                    description = t.description,
                    parameters = Parameters.fromJsonString("""{"type":"object","properties":{}}"""),
                ),
            )
        }

    private fun buildOpenAiToolsFor(toolNames: Set<String>): List<OAITool> =
        tools.snapshot()
            .filter { it.name in toolNames }
            .map { t ->
                OAITool(
                    type = ToolType.Function,
                    function = FunctionTool(
                        name = t.name,
                        description = t.description,
                        parameters = Parameters.fromJsonString(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), t.parameters)),
                    ),
                )
            }

    fun compactContext(conversationId: String) {
        val history = contextManager.history(conversationId)
        contextManager.compactOldToolRounds(history)
    }
    fun contextReport(conversationId: String): String {
        val history = contextManager.history(conversationId)
        val charsPerToken = 3
        var systemChars = 0
        var summaryChars = 0
        var chatChars = 0
        var toolCallChars = 0
        var toolResultChars = 0
        var chatMsgCount = 0
        var toolRounds = 0
        var systemBreakdown: List<Pair<String, Int>> = emptyList()

        for (msg in history) {
            when (msg.role) {
                ChatRole.System -> {
                    val content = msg.textContentOrNull().orEmpty()
                    if (content.startsWith("[context-summary]")) {
                        summaryChars += content.length
                    } else {
                        systemChars += content.length
                        if (systemBreakdown.isEmpty()) systemBreakdown = breakdownSystemPrompt(content)
                    }
                }
                ChatRole.User -> { chatChars += msg.estimatedContentChars(); chatMsgCount++ }
                ChatRole.Assistant -> {
                    val contentLen = msg.estimatedContentChars()
                    val toolLen = msg.toolCalls?.sumOf { tc ->
                        when (tc) {
                            is ToolCall.Function -> (tc.function.argumentsOrNull?.length ?: 0) + (tc.function.nameOrNull?.length ?: 0)
                            else -> 0
                        }
                    } ?: 0
                    if (msg.toolCalls.isNullOrEmpty()) {
                        chatChars += contentLen
                    } else {
                        toolCallChars += contentLen + toolLen
                        toolRounds++
                    }
                }
                ChatRole.Tool -> toolResultChars += msg.estimatedContentChars()
                else -> {}
            }
        }

        val toolSchemaChars = tools.snapshot().sumOf { t ->
            if (config.lazyToolSchema)
                t.name.length + t.description.length + 38 // 最小 schema: {"type":"object","properties":{}}
            else
                t.name.length + t.description.length + t.parameters.toString().length
        }

        val parts = listOf(
            "系统提示词" to systemChars,
            "上下文摘要" to summaryChars,
            "对话历史" to chatChars,
            "工具调用" to toolCallChars,
            "工具结果" to toolResultChars,
            "工具Schema" to toolSchemaChars,
        )
        val totalTk = parts.sumOf { it.second } / charsPerToken
        val sess = sessions[conversationId]

        return buildString {
            appendLine("=== 上下文占用 [$conversationId] ===")
            appendLine("消息: ${history.size} 条  工具轮次: $toolRounds  对话轮: $chatMsgCount")
            appendLine()
            for ((label, chars) in parts) {
                if (chars == 0) continue
                val tk = chars / charsPerToken
                val pct = if (totalTk > 0) tk * 100 / totalTk else 0
                appendLine("%-10s %5s tk  %3d%%".format(label, fmtTk(tk), pct))
                if (label == "系统提示词" && systemBreakdown.isNotEmpty()) {
                    for ((subLabel, subChars) in systemBreakdown) {
                        if (subChars > 0)
                            appendLine("  %-9s %5s tk".format("└$subLabel", fmtTk(subChars / charsPerToken)))
                    }
                }
            }
            appendLine()
            append("合计: ~${fmtTk(totalTk)} tk (字符估算，非API实际值)")
            if (sess != null) {
                appendLine()
                append("会话累计: →${fmtTk(sess.contextTokens)} tk 传入 / ←${fmtTk(sess.usedTokens)} tk 输出")
            }
        }.trimEnd()
    }

    private fun fmtTk(tk: Long): String = when {
        tk >= 1_000_000 -> "%.1fM".format(tk / 1_000_000.0)
        tk >= 1_000     -> "%.1fk".format(tk / 1_000.0)
        else            -> "$tk"
    }

    private fun fmtTk(tk: Int): String = fmtTk(tk.toLong())

    private fun breakdownSystemPrompt(prompt: String): List<Pair<String, Int>> {
        val sectionRegex = Regex("(?=\\n\\n# )")
        val sections = prompt.split(sectionRegex)
        return sections.map { section ->
            val label = when {
                section.contains("# Security") -> "安全防护"
                section.contains("# Output") -> "输出规则"
                section.contains("# Tool Usage") -> "工具指南"
                section.contains("# Available Skills") -> "技能列表"
                else -> "人设指令"
            }
            label to section.length
        }
    }

    private fun cleanupIdleSessions(now: Long = System.currentTimeMillis()) {
        val interval = config.sessionCleanupIntervalMs.coerceAtLeast(1L)
        if (now - lastSessionCleanupAt < interval) return
        lastSessionCleanupAt = now

        val idleTtl = config.sessionIdleTtlMs
        if (idleTtl > 0) {
            sessions.entries.removeIf { (_, session) -> now - session.lastAccessedAt > idleTtl }
        }

        val maxActive = config.sessionMaxActive
        if (maxActive > 0 && sessions.size > maxActive) {
            sessions.entries
                .sortedBy { it.value.lastAccessedAt }
                .take(sessions.size - maxActive)
                .forEach { sessions.remove(it.key) }
        }
    }

    override fun close() {
        AgentManager.unregister(id)
        sessions.clear()
        sessionStore?.close()
    }

    private data class CompletionResult(
        val message: ChatMessage,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    private companion object {
        /** 工具结果最大字符数，超出截断。 */
        const val MAX_TOOL_RESULT_CHARS = 30_000
    }
}
