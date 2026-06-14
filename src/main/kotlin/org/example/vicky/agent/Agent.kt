package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.chat.Parameters
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
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
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
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

    /** 子类提供：消息出口。 */
    protected abstract val sink: MessageSink

    /** 子类提供：工具权限。默认全允许可在子类中 override。 */
    protected open val authorizer: ToolAuthorizer = ToolAuthorizer.ALLOW_ALL

    /** 运行时注册工具。 */
    fun registerTool(tool: Tool) = tools.register(tool)
    fun unregisterTool(name: String): Tool? = tools.unregister(name)

    /** 入口：外部把收到的消息丢进来。 */
    suspend fun receive(msg: InboundMessage) {
        val history = store.history(msg.conversationId)
        ensureSystemPrompt(history)
        history += ChatMessage(role = ChatRole.User, content = msg.content)

        // 模式决定是否传工具（如 CHAT 不传）。
        val oaiTools = if (config.mode.toolsEnabled) buildOpenAiTools() else emptyList()

        repeat(config.maxSteps) {
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
                if (config.mode.emitAgentText) {
                    assistant.content?.takeIf { it.isNotBlank() }?.let {
                        sink.emit(OutboundMessage.AgentReply(msg.conversationId, msg.userId, it))
                    }
                }
                return
            }

            for (call in calls) {
                if (call !is ToolCall.Function) continue
                val toolName = call.function.name
                val result = invokeTool(msg, toolName, call.function.argumentsAsJson())
                history += ChatMessage(
                    role = ChatRole.Tool,
                    toolCallId = call.id,
                    name = toolName,
                    content = result.toAgent,
                )
                result.userReply?.takeIf { it.isNotBlank() }?.let {
                    sink.emit(OutboundMessage.ToolReply(msg.conversationId, msg.userId, it, toolName))
                }
            }
        }
        // 达到 maxSteps 仍未给出最终回复，静默结束。
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
        return runCatching { tool.execute(msg.userId, args) }
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
