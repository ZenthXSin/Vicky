package org.example.vicky.context

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.client.OpenAI
import org.example.vicky.agent.AgentConfig

/**
 * 上下文压缩器，提供两级上下文管理：
 *
 * 1. **轮数裁剪** ([maxMemoryRounds])：保留最近 N 轮用户消息，截断更早的消息。
 * 2. **LLM 压缩** ([maxContextLength])：当估算 token 超限时，调用 LLM 生成结构化摘要，替换旧消息。
 *
 * 在每次 LLM 请求前调用 [ensureContextBudget] 执行两级检查。
 */
class ContextCompactor(
    private val config: AgentConfig,
    private val openAi: OpenAI,
) {
    companion object {
        private const val CHARS_PER_TOKEN = 3
        private const val KEEP_RECENT_MESSAGES = 4
        private const val SUMMARY_MARKER = "[context-summary]"

        const val COMPRESSION_PROMPT = """请将以下对话历史压缩为结构化摘要，保留关键信息。使用以下格式，空的部分写"N/A"：

## 初始请求
用户的原始目标或请求。

## 已完成步骤
- 按顺序列出每个已执行的操作、调用的工具、做出的决定。

## 当前状态
目前已完成的工作，涉及的文件/代码/数据修改。

## 待办任务
仍需完成的工作。

## 技术决策
关键的架构、设计或实现选择。

## 涉及文件
所有读取、写入、引用的文件路径。

## 遇到的错误
错误及解决方案（或未解决的情况）。

## 环境信息
相关上下文：OS、语言版本、框架版本、项目结构。

## 下一步建议
建议的后续操作。

---

对话内容：
{conversation}"""
    }

    /**
     * 主入口：在每次 LLM 请求前调用。
     * 依次执行轮数裁剪和 LLM 压缩。
     */
    suspend fun ensureContextBudget(history: MutableList<ChatMessage>) {
        if (config.maxMemoryRounds > 0) {
            trimMemoryRounds(history)
        }
        if (config.maxContextLength > 0) {
            compressToContextBudget(history)
        }
    }

    // ── 机制一：轮数裁剪 ──────────────────────────────────────

    private fun trimMemoryRounds(history: MutableList<ChatMessage>) {
        val systemMsg = history.firstOrNull { it.role == ChatRole.System }
        val nonSystemMessages = history.filter { it.role != ChatRole.System }

        val userMessageIndices = nonSystemMessages
            .mapIndexedNotNull { i, m -> if (m.role == ChatRole.User) i else null }

        if (userMessageIndices.size <= config.maxMemoryRounds) return

        val roundsToTrim = userMessageIndices.size - config.maxMemoryRounds
        val cutoffNonSystemIndex = userMessageIndices[roundsToTrim]
        val cutoffHistoryIndex = cutoffNonSystemIndex + if (systemMsg != null) 1 else 0

        val kept = history.subList(cutoffHistoryIndex, history.size).toMutableList()
        history.clear()
        if (systemMsg != null) history.add(systemMsg)
        history.addAll(kept)
    }

    // ── 机制二：LLM 压缩 ──────────────────────────────────────

    private suspend fun compressToContextBudget(history: MutableList<ChatMessage>) {
        val totalTokens = history.sumOf { it.estimateChars() } / CHARS_PER_TOKEN
        if (totalTokens <= config.maxContextLength) return

        val systemMsg = if (history.firstOrNull()?.role == ChatRole.System) history.first() else null
        val preserveEnd = if (systemMsg != null) 1 else 0
        val totalNonSystem = history.size - preserveEnd
        if (totalNonSystem <= KEEP_RECENT_MESSAGES) return

        val compressStart = preserveEnd
        // 向后推进 compressEnd 跨过任何 Tool 消息，避免它们与压缩区内的 Assistant.toolCalls 失配
        var compressEnd = history.size - KEEP_RECENT_MESSAGES
        while (compressEnd < history.size && history[compressEnd].role == ChatRole.Tool) {
            compressEnd++
        }
        if (compressEnd <= compressStart) return

        val toCompress = history.subList(compressStart, compressEnd).toList()
        val toKeepRecent = history.subList(compressEnd, history.size).toList()

        val summary = callLLMForSummary(toCompress)

        history.clear()
        if (systemMsg != null) history.add(systemMsg)
        history.add(
            ChatMessage(
                role = ChatRole.System,
                content = "$SUMMARY_MARKER\n$summary",
            ),
        )
        history.addAll(toKeepRecent)
    }

    private suspend fun callLLMForSummary(messages: List<ChatMessage>): String {
        val formattedMessages = messages.joinToString("\n\n") { msg ->
            val role = when (msg.role) {
                ChatRole.User -> "User"
                ChatRole.Assistant -> "Assistant"
                ChatRole.Tool -> "Tool(${msg.name ?: "?"})"
                else -> msg.role.toString()
            }
            val content = msg.content?.take(2000) ?: "(empty)"
            "[$role]: $content"
        }

        val prompt = COMPRESSION_PROMPT.replace("{conversation}", formattedMessages)

        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = "You are a conversation summarizer."),
                ChatMessage(role = ChatRole.User, content = prompt),
            ),
            temperature = 0.0,
        )

        return try {
            openAi.chatCompletion(request).choices.first().message.content
                ?: "(summary generation returned empty)"
        } catch (e: Exception) {
            "(summary generation failed: ${e.message})"
        }
    }

    private fun ChatMessage.estimateChars(): Int {
        val contentLen = (content ?: "").length
        val toolCallsLen = toolCalls?.sumOf { tc ->
            when (tc) {
                is com.aallam.openai.api.chat.ToolCall.Function -> tc.function.arguments.length
                else -> 0
            }
        } ?: 0
        return contentLen + toolCallsLen
    }
}