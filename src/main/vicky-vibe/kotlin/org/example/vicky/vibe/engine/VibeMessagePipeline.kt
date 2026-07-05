package org.example.vicky.vibe.engine

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import org.example.vicky.agent.ChatCompletionRunner
import org.example.vicky.agent.InlineToolCallParser
import org.example.vicky.agent.OpenAiToolSchemaBuilder
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolContext
import org.example.vicky.vibe.tool.VibeToolUse
import org.example.vicky.vibe.tool.VibeToolUseQueue
import org.example.vicky.vibe.turn.VibeTurnRequest
import org.example.vicky.vibe.turn.VibeTurnResult

class VibeMessagePipeline(
    private val request: VibeTurnRequest,
    private val completionRunner: ChatCompletionRunner,
) {
    suspend fun run(): VibeTurnResult {
        val history = request.contextManager.history(request.inbound.conversationId)
        if (request.resetContext) history.clear()
        if (history.isEmpty() || history.first().role != ChatRole.System) {
            history.add(0, ChatMessage(role = ChatRole.System, content = request.config.agentMd))
        } else {
            history[0] = ChatMessage(role = ChatRole.System, content = request.config.agentMd)
        }
        history += ChatMessage(role = ChatRole.User, content = request.inbound.content)

        val oaiTools = if (request.config.mode.toolsEnabled) OpenAiToolSchemaBuilder.build(request.tools) else emptyList()
        val toolUses = mutableListOf<VibeToolUse>()
        var promptTokens = 0
        var completionTokens = 0
        var assistantReply: String? = null
        var stepCount = 0
        var wrapUpMessage: ChatMessage? = null

        suspend fun emitDebug(message: String) {
            if (request.config.debug) {
                request.sink.emit(OutboundMessage.Debug(request.inbound.conversationId, request.inbound.userId, request.inbound.groupId, message))
            }
        }

        suspend fun emitThink(message: String) {
            if (request.config.think) {
                request.sink.emit(OutboundMessage.Think(request.inbound.conversationId, request.inbound.userId, request.inbound.groupId, message))
            }
        }

        return try {
            repeat(request.config.maxSteps) { step ->
                stepCount = step + 1
                request.contextManager.ensureContextBudget(history)
                emitDebug("vibe step ${step + 1}/${request.config.maxSteps} -> requesting completion (${history.size} msgs)")
                val completion = completionRunner.complete(
                    ChatCompletionRequest(
                        model = request.config.model,
                        messages = history.toList(),
                        tools = oaiTools.takeIf { it.isNotEmpty() },
                        temperature = request.config.temperature,
                    ),
                    onDebug = if (request.config.debug) { s -> emitDebug(s) } else null,
                )
                promptTokens += completion.promptTokens
                completionTokens += completion.completionTokens
                request.sink.emit(
                    OutboundMessage.TokenUsage(
                        request.inbound.conversationId,
                        request.inbound.userId,
                        request.inbound.groupId,
                        completion.promptTokens,
                        completion.completionTokens,
                        promptTokens + completionTokens,
                    ),
                )

                val assistant = completion.message
                history += assistant
                var calls = assistant.toolCalls.orEmpty().filterIsInstance<ToolCall.Function>()
                if (calls.isEmpty()) {
                    calls = InlineToolCallParser.parse(assistant.content.orEmpty()).filterIsInstance<ToolCall.Function>()
                    if (calls.isNotEmpty()) emitDebug("vibe step ${step + 1}: parsed ${calls.size} inline tool calls")
                }

                if (calls.isEmpty()) {
                    assistantReply = assistant.content
                    if (request.config.mode.emitAgentText) {
                        assistantReply?.takeIf { it.isNotBlank() }?.let {
                            request.sink.emit(OutboundMessage.AgentReply(request.inbound.conversationId, request.inbound.userId, request.inbound.groupId, it))
                        }
                    }
                    return VibeTurnResult(assistantReply, toolUses, promptTokens, completionTokens, success = true, stepCount = stepCount)
                }

                assistant.content?.takeIf { it.isNotBlank() }?.let { emitThink(it) }
                for (call in calls) emitThink("Use Tool: ${call.function.name}")

                val queue = VibeToolUseQueue(
                    tools = request.tools,
                    context = ToolContext(request.inbound.userId, request.inbound.conversationId, request.inbound.groupId, request.contextManager, request.tools, request.buffer),
                    authorizer = request.authorizer,
                    sink = request.sink,
                    taskGraph = request.taskGraph,
                )
                val toolResult = queue.execute(calls)
                history += toolResult.messages
                toolUses += toolResult.toolUses
                request.contextManager.ensureContextBudget(history)
                if (toolResult.endTurn) {
                    assistantReply = assistant.content
                    return VibeTurnResult(assistantReply, toolUses, promptTokens, completionTokens, success = true, stepCount = stepCount)
                }
            }

            emitDebug("vibe reached maxSteps (${request.config.maxSteps}); requesting wrap-up")
            val wrapUpPrompt = ChatMessage(
                role = ChatRole.System,
                content = "System notice: the step budget for this turn is exhausted; no more tools can be called. Summarize the current situation and explicitly note any incomplete actions. Reply in the user's language.",
            )
            history += wrapUpPrompt
            wrapUpMessage = wrapUpPrompt
            val wrapUp = completionRunner.complete(
                ChatCompletionRequest(
                    model = request.config.model,
                    messages = history.toList(),
                    temperature = request.config.temperature,
                ),
                onDebug = if (request.config.debug) { s -> emitDebug(s) } else null,
            )
            promptTokens += wrapUp.promptTokens
            completionTokens += wrapUp.completionTokens
            history += wrapUp.message
            assistantReply = wrapUp.message.content
            assistantReply?.takeIf { it.isNotBlank() }?.let {
                request.sink.emit(OutboundMessage.AgentReply(request.inbound.conversationId, request.inbound.userId, request.inbound.groupId, it))
            }
            VibeTurnResult(assistantReply, toolUses, promptTokens, completionTokens, success = false, error = "maxSteps exhausted", stepCount = stepCount)
        } catch (e: Throwable) {
            emitDebug("vibe failed: ${e::class.simpleName}: ${e.message}")
            VibeTurnResult(assistantReply, toolUses, promptTokens, completionTokens, success = false, error = "${e::class.simpleName}: ${e.message}", stepCount = stepCount)
        } finally {
            wrapUpMessage?.let { history.remove(it) }
            request.contextManager.compactOldToolRounds(history)
            request.contextManager.trimIfNeeded(request.inbound.conversationId)
        }
    }
}
