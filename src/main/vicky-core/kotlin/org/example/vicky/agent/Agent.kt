package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolType
import com.aallam.openai.client.OpenAI
import kotlinx.serialization.json.Json
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
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

    private var memoryInitialized = false

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

    /**
     * 入口：外部把收到的消息丢进来。
     */
    suspend fun receive(
        msg: InboundMessage,
        replySink: MessageSink? = null,
        clearContextAfter: Boolean = false,
    ) {
        if (!memoryInitialized) {
            memoryInitialized = true
            initMemory()
        }
        val history = contextManager.history(msg.conversationId)

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

        try {
            repeat(config.maxSteps) { step ->
                log("step ${step + 1}/${config.maxSteps} -> requesting completion (${history.size} msgs)")
                val request = ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    tools = oaiTools.takeIf { it.isNotEmpty() },
                    temperature = config.temperature,
                )
                val assistant = completeChat(request, onDebug = if (config.debug) { s -> log(s) } else null)
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
                contextManager.compactOldToolRounds(history)
                contextManager.ensureContextBudget(history)
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
            val wrapUp = completeChat(
                ChatCompletionRequest(
                    model = config.model,
                    messages = history.toList(),
                    temperature = config.temperature,
                ),
                onDebug = if (config.debug) { s -> log(s) } else null,
            )
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
            onTurnEnd(msg, assistantReply)

            if (clearContextAfter) {
                contextManager.clear(msg.conversationId)
                log("cleared context for '${msg.conversationId}' after reply")
            } else {
                contextManager.trimIfNeeded(msg.conversationId)
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
     */
    private suspend fun completeChat(
        request: ChatCompletionRequest,
        onDebug: (suspend (String) -> Unit)? = null,
    ): ChatMessage {
        if (!config.streaming) {
            return openAi.chatCompletion(request).choices.first().message
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
        try {
            openAi.chatCompletions(request).collect { chunk ->
                chunkCount++
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
        return ChatMessage(
            role = role,
            content = contentBuf.toString().ifEmpty { null },
            toolCalls = builtToolCalls.ifEmpty { null },
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

    override fun close() {
        AgentManager.unregister(id)
    }
}
