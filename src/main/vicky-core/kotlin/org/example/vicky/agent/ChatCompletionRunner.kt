package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.StreamOptions
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ChatCompletionRunner(
    private val openAi: OpenAI,
    private val config: AgentConfig,
) {
    suspend fun complete(
        request: ChatCompletionRequest,
        onDebug: (suspend (String) -> Unit)? = null,
        onDelta: (suspend (String) -> Unit)? = null,
    ): CompletionResult {
        var lastException: Throwable? = null

        repeat(config.llmMaxRetries + 1) { attempt ->
            try {
                return withTimeout(config.llmTimeoutMs) {
                    completeOnce(request, onDebug, onDelta)
                }
            } catch (e: Throwable) {
                lastException = e
                if (attempt < config.llmMaxRetries) {
                    val delayMs = 1000L * (attempt + 1)
                    onDebug?.invoke("[retry] attempt ${attempt + 1} failed: ${e::class.simpleName}: ${e.message}, retrying in ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
        throw lastException!!
    }

    private suspend fun completeOnce(
        request: ChatCompletionRequest,
        onDebug: (suspend (String) -> Unit)? = null,
        onDelta: (suspend (String) -> Unit)? = null,
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
        data class ToolCallAccum(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder())
        val toolCalls = linkedMapOf<Int, ToolCallAccum>()
        var role: ChatRole = ChatRole.Assistant
        var chunkCount = 0
        var contentChunkCount = 0
        var toolChunkCount = 0
        var finishReason: String? = null
        var streamPromptTokens = 0
        var streamCompletionTokens = 0
        val streamRequest = request.copy(streamOptions = StreamOptions(includeUsage = true))

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
                delta.content?.let {
                    contentBuf.append(it)
                    contentChunkCount++
                    onDelta?.invoke(it)
                }
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
                id = ToolId(id),
                function = FunctionCall(
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
}
