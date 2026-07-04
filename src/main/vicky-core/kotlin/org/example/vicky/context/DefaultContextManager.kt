package org.example.vicky.context

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import org.example.vicky.agent.AgentMode
import org.example.vicky.tool.ToolRegistry

/**
 * [ContextManager] 的默认实现，组合三个现有组件：
 * - [ConversationStore] — 内存会话历史
 * - [ContextBuilder] — system prompt 拼接
 * - [ContextCompactor] — 上下文压缩（轮数裁剪 + LLM 摘要）
 */
class DefaultContextManager(
    private val store: ConversationStore,
    private val builder: ContextBuilder,
    private val compactor: ContextCompactor,
) : ContextManager {

    override fun history(conversationId: String): MutableList<ChatMessage> =
        store.history(conversationId)

    override fun buildSystemPrompt(mode: AgentMode, tools: ToolRegistry): String =
        builder.systemPrompt(mode, tools)

    override suspend fun ensureContextBudget(history: MutableList<ChatMessage>) {
        compactor.ensureContextBudget(history)
    }

    override fun compactOldToolRounds(history: MutableList<ChatMessage>) {
        val roundStarts = history.withIndex()
            .filter { (_, m) -> m.role == ChatRole.Assistant && !m.toolCalls.isNullOrEmpty() }
            .map { it.index }
        if (roundStarts.size <= KEEP_RECENT_TOOL_ROUNDS) return
        val cutoff = roundStarts[roundStarts.size - KEEP_RECENT_TOOL_ROUNDS]
        for (i in 0 until cutoff) {
            val m = history[i]
            when {
                m.role == ChatRole.Tool -> {
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
                m.role == ChatRole.Assistant && !m.toolCalls.isNullOrEmpty() -> {
                    val names = m.toolCalls!!.mapNotNull {
                        (it as? com.aallam.openai.api.chat.ToolCall.Function)?.function?.nameOrNull
                    }.joinToString(", ")
                    history[i] = ChatMessage(
                        role = ChatRole.Assistant,
                        content = "$SUMMARY_PREFIX called: $names",
                    )
                }
            }
        }
    }

    override fun clear(conversationId: String) {
        store.clear(conversationId)
    }

    override fun trimIfNeeded(conversationId: String) {
        store.trimIfNeeded(conversationId)
    }

    private companion object {
        const val KEEP_RECENT_TOOL_ROUNDS = 2
        const val SUMMARY_PREFIX = "[summary] "
    }
}
