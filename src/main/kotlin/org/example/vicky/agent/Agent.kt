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
import org.example.vicky.context.ContextBuilder
import org.example.vicky.context.ConversationStore
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import org.example.vicky.tool.builtin.BuiltinTools
import com.aallam.openai.api.chat.Tool as OAITool

/**
 * Agent 抽象基类。子类负责提供消息出口 ([sink]) 与权限策略 ([authorizer])，
 * 并通过 [receive] 把入站消息送进来。
 *
 * 主循环 (每条入站消息独立运行)：
 * ```
 * for step in 0 until maxSteps:
 *   resp = chat.completion(history + tools)
 *   if resp.toolCalls 非空:
 *     for tc in toolCalls:
 *       校验权限 -> 执行工具 -> result
 *       history += assistantToolCall
 *       history += toolMessage(result.toAgent)
 *       result.userReply?.let { sink.emit(ToolReply) }   // 实时输出
 *     continue
 *   else:
 *     if mode == VERBOSE: sink.emit(AgentReply(resp.content))
 *     history += assistantMessage
 *     break
 * ```
 */
abstract class Agent(
    protected val config: AgentConfig,
    private val openAi: OpenAI = OpenAiClientFactory.create(config),
    protected val contextBuilder: ContextBuilder = ContextBuilder(config.agentMd),
    protected val store: ConversationStore = ConversationStore(),
) {
    val tools: ToolRegistry = ToolRegistry()

    init {
        if (config.builtinTools) BuiltinTools.all().forEach { tools.register(it) }
    }

    /** 子类提供：消息出口。 */
    protected abstract val sink: MessageSink

    /** 子类提供：工具权限。默认全允许可在子类中 override。 */
    protected open val authorizer: ToolAuthorizer = ToolAuthorizer.ALLOW_ALL

    /** 运行时注册工具。 */
    fun registerTool(tool: Tool) = tools.register(tool)
    fun unregisterTool(name: String): Tool? = tools.unregister(name)

    /**
     * 入口：外部把收到的消息丢进来。
     * @param replySink 可选的单次出口，每条面向 user 的输出都会**实时**发一份。
     *                  与构造时注入的 [sink] 并存，两者都会收到。
     * @param clearContextAfter 为 true 时，本轮结束 (给出最终回复或耗尽 maxSteps) 后自动清空该会话上下文，
     *                          实现「单次无状态」请求。下一条消息从空历史开始。
     */
    suspend fun receive(
        msg: InboundMessage,
        replySink: MessageSink? = null,
        clearContextAfter: Boolean = false,
    ) {
        val history = store.history(msg.conversationId)
        ensureSystemPrompt(history)
        history += ChatMessage(role = ChatRole.User, content = msg.content)

        // 同时发到注入的 sink 和（可选）单次 replySink。
        suspend fun emit(out: OutboundMessage) {
            sink.emit(out)
            replySink?.emit(out)
        }

        // 日志走 MessageSink，由外部决定如何呈现/丢弃。
        suspend fun log(message: String) {
            if (config.debug) emit(OutboundMessage.Debug(msg.conversationId, msg.userId, message))
        }

        suspend fun logThink(content: String) {
            if (config.think) emit(OutboundMessage.Think(msg.conversationId, msg.userId, content))
        }

        // 模式决定是否传工具（如 CHAT 不传）。
        val oaiTools = if (config.mode.toolsEnabled) buildOpenAiTools() else emptyList()

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
                if (config.mode.emitAgentText) {
                    assistant.content?.takeIf { it.isNotBlank() }?.let {
                        emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, it))
                    }
                }
                if (clearContextAfter) {
                    store.clear(msg.conversationId)
                    log("cleared context for '${msg.conversationId}' after reply")
                }
                return
            }

            // 中间过程才算 think：模型的思考文本 + 即将调用的工具 (最后一轮的 content 是最终回答，不算)。
            assistant.content?.takeIf { it.isNotBlank() }?.let { logThink(it) }

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
                    emit(OutboundMessage.ToolReply(msg.conversationId, msg.userId, it, toolName))
                }
            }
        }
        // 步数耗尽：不再给工具，让模型基于已有信息整理现状并向用户汇报。
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
        wrapUp.content?.takeIf { it.isNotBlank() }?.let {
            emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, it))
        }
        if (clearContextAfter) store.clear(msg.conversationId)
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
        val ctx = ToolContext(msg.userId, msg.conversationId, store, tools)
        return runCatching { tool.execute(ctx, args) }
            .getOrElse { ToolResult(toAgent = "Error executing '$toolName': ${it.message}") }
    }

    private fun ensureSystemPrompt(history: MutableList<ChatMessage>) {
        val prompt = contextBuilder.systemPrompt(config.mode, tools)
        if (history.isEmpty() || history.first().role != ChatRole.System) {
            history.add(0, ChatMessage(role = ChatRole.System, content = prompt))
        } else {
            // 每轮刷新 system，工具增删能立刻反映。
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
}
