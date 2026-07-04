package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolType
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
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

    fun session(conversationId: String): Session =
        sessions.getOrPut(conversationId) { Session(conversationId, sessionStore, this) }

    suspend fun clearContext(conversationId: String) {
        contextManager.clear(conversationId)
        session(conversationId).delete()
        sessions.remove(conversationId)
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

        history += ChatMessage(role = ChatRole.User, content = msg.content)

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

        val oaiTools = if (config.mode.toolsEnabled) buildOpenAiTools() else emptyList()
        contextManager.ensureContextBudget(history)

        var assistantReply: String? = null
        // 记录 wrap-up 注入的假消息，finally 里清理
        var wrapUpMessage: ChatMessage? = null

        try {
            repeat(config.maxSteps) { step ->
                val ctxEstimate = history.sumOf { (it.content ?: "").length } / 3
                log("step ${step + 1}/${config.maxSteps} -> requesting completion (${history.size} msgs, ~${ctxEstimate}tk in)")
                val request = ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    tools = oaiTools.takeIf { it.isNotEmpty() },
                    temperature = config.temperature,
                )
                val cr = completeChat(request, onDebug = if (config.debug) { s -> log(s) } else null)
                sess.contextTokens += cr.promptTokens
                sess.usedTokens += cr.completionTokens
                emit(OutboundMessage.TokenUsage(msg.conversationId, msg.userId, msg.groupId, cr.promptTokens, cr.completionTokens, sess.usedTokens))
                val assistant = cr.message
                history += assistant

                var calls = assistant.toolCalls.orEmpty()
                // 兜底：模型把工具调用输出在 content 里（如 DeepSeek DSML 格式）
                if (calls.isEmpty()) {
                    val parsed = parseInlineToolCalls(assistant.content.orEmpty())
                    if (parsed.isNotEmpty()) {
                        calls = parsed
                        log("step ${step + 1}: parsed ${parsed.size} inline tool calls from content")
                    }
                }
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
            val wrapUpCr = completeChat(
                ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    temperature = config.temperature,
                ),
                onDebug = if (config.debug) { s -> log(s) } else null,
            )
            sess.contextTokens += wrapUpCr.promptTokens
            sess.usedTokens += wrapUpCr.completionTokens
            emit(OutboundMessage.TokenUsage(msg.conversationId, msg.userId, msg.groupId, wrapUpCr.promptTokens, wrapUpCr.completionTokens, sess.usedTokens))
            val wrapUp = wrapUpCr.message
            history += wrapUp
            assistantReply = wrapUp.content
            wrapUp.content?.takeIf { it.isNotBlank() }?.let {
                emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, msg.groupId, it))
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
                session(msg.conversationId).delete()
                sessions.remove(msg.conversationId)
                log("cleared context for '${msg.conversationId}' after reply")
            } else {
                contextManager.trimIfNeeded(msg.conversationId)
                session(msg.conversationId).flush(history)
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

    /**
     * 统一的 chat completion 入口：根据 [AgentConfig.streaming] 选择流式或一次性请求。
     * 两种模式的返回值语义一致：一条完整的 assistant [ChatMessage]，含拼装好的 content 与 toolCalls。
     * 内置超时与重试。
     */
    private suspend fun completeChat(
        request: ChatCompletionRequest,
        onDebug: (suspend (String) -> Unit)? = null,
    ): CompletionResult {
        val timeoutMs = config.llmTimeoutMs
        val maxRetries = config.llmMaxRetries
        var lastException: Throwable? = null

        repeat(maxRetries + 1) { attempt ->
            try {
                return withTimeout(timeoutMs) {
                    completeChatOnce(request, onDebug)
                }
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxRetries) {
                    val delayMs = 1000L * (attempt + 1)
                    onDebug?.invoke("[retry] attempt ${attempt + 1} failed: ${e::class.simpleName}: ${e.message}, retrying in ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastException!!
    }

    private suspend fun completeChatOnce(
        request: ChatCompletionRequest,
        onDebug: (suspend (String) -> Unit)? = null,
    ): CompletionResult {
        if (!config.streaming) {
            val completion = openAi.chatCompletion(request)
            return CompletionResult(
                message = completion.choices.first().message,
                promptTokens = completion.usage?.promptTokens ?: 0,
                completionTokens = completion.usage?.completionTokens ?: 0,
            )
        }
        val contentBuf = StringBuilder()
        // toolCall 累积：index -> (id, name, argumentsBuf)
        data class ToolCallAccum(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder())
        val toolCalls = linkedMapOf<Int, ToolCallAccum>()
        var role: ChatRole = ChatRole.Assistant
        var chunkCount = 0
        var contentChunkCount = 0
        var toolChunkCount = 0
        var finishReason: String? = null
        var streamPromptTokens = 0
        var streamCompletionTokens = 0
        val streamRequest = request.copy(
            streamOptions = com.aallam.openai.api.chat.StreamOptions(includeUsage = true),
        )
        try {
            openAi.chatCompletions(streamRequest).collect { chunk ->
                chunkCount++
                chunk.usage?.let {
                    streamPromptTokens = it.promptTokens ?: 0
                    streamCompletionTokens = it.completionTokens ?: 0
                }
                val choice = chunk.choices.firstOrNull() ?: return@collect
                choice.finishReason?.let { finishReason = it.value }
                val delta = choice.delta ?: return@collect
                delta.role?.let { role = it }
                delta.content?.let { contentBuf.append(it); contentChunkCount++ }
                delta.toolCalls?.forEach { tcc ->
                    toolChunkCount++
                    val accum = toolCalls.getOrPut(tcc.index) { ToolCallAccum() }
                    tcc.id?.takeIf { it.id.isNotBlank() }?.let { accum.id = it.id }
                    tcc.function?.nameOrNull?.takeIf { it.isNotBlank() }?.let { accum.name = it }
                    tcc.function?.argumentsOrNull?.let { accum.args.append(it) }
                }
            }
        } catch (e: Throwable) {
            onDebug?.invoke("[stream] collect threw ${e::class.simpleName}: ${e.message} (after $chunkCount chunks, content=$contentChunkCount, toolDeltas=$toolChunkCount)")
            throw e
        }
        onDebug?.invoke("[stream] done: chunks=$chunkCount content=$contentChunkCount toolDeltas=$toolChunkCount finish=$finishReason toolCallAccums=${toolCalls.size}")
        if (chunkCount == 0) {
            throw IllegalStateException("流式响应未收到任何 chunk（网关可能未真正推送 SSE 数据）")
        }
        if (contentChunkCount == 0 && toolCalls.isEmpty()) {
            throw IllegalStateException("流式响应有 $chunkCount 个 chunk，但 content 与 tool_calls 全空（网关可能吞掉了 delta，finish=$finishReason）")
        }
        if (onDebug != null) {
            for ((idx, acc) in toolCalls) {
                onDebug("[stream] raw accum[$idx]: id=${acc.id} name=${acc.name} argsLen=${acc.args.length}")
            }
        }
        val validAccums = toolCalls.values
            .filter { !it.name.isNullOrBlank() }
            .map { acc ->
                val argsStr = acc.args.toString().ifBlank { "{}" }
                val id = acc.id?.takeIf { it.isNotBlank() } ?: "call_${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
                Triple(acc, id, argsStr)
            }
        if (onDebug != null) {
            for ((acc, id, argsStr) in validAccums) {
                onDebug("[stream] tool_call assembled: name=${acc.name} id=$id args=${argsStr.take(300)}")
            }
        }
        val builtToolCalls = validAccums.map { (acc, id, argsStr) ->
            ToolCall.Function(
                id = com.aallam.openai.api.chat.ToolId(id),
                function = com.aallam.openai.api.chat.FunctionCall(
                    nameOrNull = acc.name,
                    argumentsOrNull = argsStr,
                ),
            )
        }
        return CompletionResult(
            message = ChatMessage(
                role = role,
                content = contentBuf.toString().ifEmpty { null },
                toolCalls = builtToolCalls.ifEmpty { null },
            ),
            promptTokens = streamPromptTokens,
            completionTokens = streamCompletionTokens,
        )
    }

    private fun buildOpenAiTools(): List<OAITool> {
        cachedOaiTools?.let { return it }
        val result = tools.snapshot().map { t ->
            OAITool(
                type = ToolType.Function,
                function = FunctionTool(
                    name = t.name,
                    description = t.description,
                    parameters = Parameters.fromJsonString(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), t.parameters)),
                ),
            )
        }
        cachedOaiTools = result
        return result
    }

    /**
     * 兜底解析：模型把工具调用以文本形式输出在 content 里（如 DeepSeek DSML 格式）。
     * 格式示例：
     * <｜｜DSML｜｜tool_calls>
     * <｜｜DSML｜｜invoke name="tool_name">
     * <｜｜DSML｜｜parameter name="key">value</｜｜DSML｜｜parameter>
     * </｜｜DSML｜｜invoke>
     * </｜｜DSML｜｜tool_calls>
     */
    private fun parseInlineToolCalls(content: String): List<ToolCall> {
        if (!content.contains("DSML")) return emptyList()
        val results = mutableListOf<ToolCall>()
        val invokePattern = Regex(
            """<\|?\|?DSML\|?\|?invoke\s+name="([^"]+)">(.*?)</\|?\|?DSML\|?\|?invoke>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val paramPattern = Regex(
            """<\|?\|?DSML\|?\|?parameter\s+name="([^"]+)"[^>]*>(.*?)</\|?\|?DSML\|?\|?parameter>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for ((idx, match) in invokePattern.findAll(content).withIndex()) {
            val toolName = match.groupValues[1]
            val body = match.groupValues[2]
            val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            for (paramMatch in paramPattern.findAll(body)) {
                val key = paramMatch.groupValues[1]
                val rawValue = paramMatch.groupValues[2].trim()
                val jsonValue = rawValue.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: rawValue.toLongOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: rawValue.toBooleanStrictOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: kotlinx.serialization.json.JsonPrimitive(rawValue)
                args[key] = jsonValue
            }
            results += ToolCall.Function(
                id = com.aallam.openai.api.chat.ToolId("inline_${idx}_$toolName"),
                function = com.aallam.openai.api.chat.FunctionCall(
                    nameOrNull = toolName,
                    argumentsOrNull = kotlinx.serialization.json.JsonObject(args).toString(),
                ),
            )
        }
        return results
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
                    val content = msg.content ?: ""
                    if (content.startsWith("[context-summary]")) {
                        summaryChars += content.length
                    } else {
                        systemChars += content.length
                        if (systemBreakdown.isEmpty()) systemBreakdown = breakdownSystemPrompt(content)
                    }
                }
                ChatRole.User -> { chatChars += (msg.content ?: "").length; chatMsgCount++ }
                ChatRole.Assistant -> {
                    val contentLen = (msg.content ?: "").length
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
                ChatRole.Tool -> toolResultChars += (msg.content ?: "").length
                else -> {}
            }
        }

        val toolSchemaChars = tools.snapshot().sumOf { t ->
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
                appendLine("%-10s %5d tk  %3d%%".format(label, tk, pct))
                if (label == "系统提示词" && systemBreakdown.isNotEmpty()) {
                    for ((subLabel, subChars) in systemBreakdown) {
                        if (subChars > 0)
                            appendLine("  %-9s %5d tk".format("└$subLabel", subChars / charsPerToken))
                    }
                }
            }
            appendLine()
            append("合计: ~$totalTk tk (字符估算，非API实际值)")
            if (sess != null) {
                appendLine()
                append("本轮: →${sess.contextTokens}tk 传入 / ←${sess.usedTokens}tk 输出")
            }
        }.trimEnd()
    }

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

    override fun close() {
        AgentManager.unregister(id)
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
