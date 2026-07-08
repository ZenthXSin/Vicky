package org.example.vicky.vibe.tool

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import org.example.vicky.vibe.task.TaskGraph
import org.example.vicky.vibe.task.VibeTaskStatus

class VibeToolUseQueue(
    private val tools: ToolRegistry,
    private val context: ToolContext,
    private val authorizer: ToolAuthorizer,
    private val sink: MessageSink,
    private val taskGraph: TaskGraph? = null,
) {
    suspend fun execute(calls: List<ToolCall.Function>): VibeToolUseResult {
        val messages = mutableListOf<ChatMessage>()
        val toolUses = mutableListOf<VibeToolUse>()
        var endTurn = false

        for (call in calls) {
            val toolName = call.function.name
            val task = taskGraph?.create(subject = toolName)
            updateTask(task?.id, VibeTaskStatus.IN_PROGRESS)

            val rawArgs = call.function.argumentsOrNull.orEmpty()
            val parsedArgs = runCatching { call.function.argumentsAsJson() }
                .getOrElse { JsonObject(emptyMap()) }
            val result = invoke(toolName, parsedArgs)
            val success = !result.toAgent.startsWith("Error:")
            val toolContent = result.toAgent.let {
                if (it.length > MAX_TOOL_RESULT_CHARS) it.take(MAX_TOOL_RESULT_CHARS) + "\n...(truncated)" else it
            }

            messages += ChatMessage(
                role = ChatRole.Tool,
                toolCallId = call.id,
                name = toolName,
                content = toolContent,
            )
            toolUses += VibeToolUse(
                id = call.id.id,
                name = toolName,
                arguments = rawArgs,
                success = success,
                result = toolContent,
            )
            updateTask(task?.id, if (success) VibeTaskStatus.COMPLETED else VibeTaskStatus.FAILED, toolContent)
            result.userReply?.takeIf { it.isNotBlank() }?.let {
                runCatching { sink.emit(OutboundMessage.ToolReply(context.conversationId, context.userId, context.groupId, it, toolName)) }
            }
            if (result.endTurn) endTurn = true
        }

        return VibeToolUseResult(messages, toolUses, endTurn)
    }

    private suspend fun invoke(toolName: String, args: JsonObject): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult(toAgent = "Error: tool '$toolName' not found.")
        if (!authorizer.allow(context.userId, toolName)) {
            return ToolResult(toAgent = "Error: permission denied for user '${context.userId}' on tool '$toolName'.")
        }
        return try {
            withTimeout(TOOL_TIMEOUT_MS) { tool.execute(context, args) }
        } catch (e: TimeoutCancellationException) {
            ToolResult(toAgent = "Error executing '$toolName': timed out after ${TOOL_TIMEOUT_MS / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolResult(toAgent = "Error executing '$toolName': ${e.message}")
        }
    }

    private suspend fun updateTask(id: String?, status: VibeTaskStatus, result: String? = null) {
        val graph = taskGraph ?: return
        if (id == null) return
        runCatching {
            graph.update(id, status, result)
            graph.get(id)?.let { emitTaskUpdate(it) }
        }
    }

    private suspend fun emitTaskUpdate(task: org.example.vicky.vibe.task.VibeTask) {
        sink.emit(OutboundMessage.Debug(context.conversationId, context.userId, context.groupId, "[task:${task.status}] ${task.subject}"))
    }

    private companion object {
        const val MAX_TOOL_RESULT_CHARS = 30_000
        const val TOOL_TIMEOUT_MS = 90_000L
    }
}
