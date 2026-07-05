package org.example.vicky.vibe.agent

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import org.example.vicky.agent.Agent
import org.example.vicky.agent.AgentConfig
import org.example.vicky.agent.AgentMode
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.llm.OpenAiClientFactory
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolRegistry

class VibeAgent(
    config: AgentConfig,
) : Agent(config, OpenAiClientFactory.create(config)) {

    public override var sink: MessageSink = MessageSink { /* silent */ }

    override val contextManager: ContextManager = VibeContextManager(config)

    override val authorizer = ToolAuthorizer.ALLOW_ALL

    val vibeSink: MessageSink get() = sink
    val vibeContextManager: ContextManager get() = contextManager
    val vibeAuthorizer: ToolAuthorizer get() = authorizer

    var lastAssistantReply: String? = null
        private set

    override suspend fun onTurnEnd(msg: InboundMessage, assistantReply: String?) {
        lastAssistantReply = assistantReply
    }
}

/**
 * Vibe 阶段专用 ContextManager：
 * - 执行过程中不限制轮次和上下文长度
 * - 仅在上下文接近模型上限时压缩
 * - 阶段结束后可手动触发压缩
 */
private class VibeContextManager(
    private val config: AgentConfig,
) : ContextManager {
    private val history = mutableListOf<ChatMessage>()

    companion object {
        private const val CHARS_PER_TOKEN = 4
        /** 保留 20% 余量，避免请求刚好卡在上限 */
        private const val SAFETY_RATIO = 0.8
        /** 默认模型上下文窗口（未配置 maxContextLength 时使用） */
        private const val DEFAULT_CONTEXT_WINDOW = 128_000
    }

    override fun history(conversationId: String): MutableList<ChatMessage> = history

    override fun buildSystemPrompt(mode: AgentMode, tools: ToolRegistry): String = config.agentMd

    override suspend fun ensureContextBudget(history: MutableList<ChatMessage>) {
        // 执行过程中不限制，仅在真正接近模型上限时压缩
        val limit = if (config.maxContextLength > 0) config.maxContextLength else DEFAULT_CONTEXT_WINDOW
        val estimatedTokens = history.sumOf { estimateTokens(it) }
        if (estimatedTokens > limit * SAFETY_RATIO) {
            compressToBudget(history, limit)
        }
    }

    override fun compactOldToolRounds(history: MutableList<ChatMessage>) {
        // 不主动压缩 tool rounds，让 ensureContextBudget 统一处理
    }

    override fun clear(conversationId: String) {
        history.clear()
    }

    override fun trimIfNeeded(conversationId: String) {
        // 不需要裁剪
    }

    /** 阶段结束后调用：压缩上下文以便传递给下一阶段 */
    fun compressAfterStage() {
        val limit = if (config.maxContextLength > 0) config.maxContextLength else DEFAULT_CONTEXT_WINDOW
        val estimatedTokens = history.sumOf { estimateTokens(it) }
        if (estimatedTokens > limit * SAFETY_RATIO) {
            compressToBudget(history, limit)
        }
    }

    private fun compressToBudget(history: MutableList<ChatMessage>, limit: Int) {
        val systemMsg = history.firstOrNull { it.role == ChatRole.System }
        val nonSystem = history.filter { it.role != ChatRole.System }
        if (nonSystem.isEmpty()) return

        // 保留最近的消息，丢弃最早的，直到低于限制
        val targetTokens = (limit * SAFETY_RATIO).toInt()
        val kept = mutableListOf<ChatMessage>()
        var tokens = 0
        for (msg in nonSystem.reversed()) {
            val msgTokens = estimateTokens(msg)
            if (tokens + msgTokens > targetTokens && kept.isNotEmpty()) break
            kept.add(0, msg)
            tokens += msgTokens
        }

        history.clear()
        if (systemMsg != null) history.add(systemMsg)
        history.addAll(kept)
    }

    private fun estimateTokens(msg: ChatMessage): Int {
        val contentLen = (msg.content ?: "").length
        val toolCallsLen = msg.toolCalls?.sumOf { tc ->
            when (tc) {
                is com.aallam.openai.api.chat.ToolCall.Function -> tc.function.arguments.length
            }
        } ?: 0
        return (contentLen + toolCallsLen) / CHARS_PER_TOKEN
    }
}
