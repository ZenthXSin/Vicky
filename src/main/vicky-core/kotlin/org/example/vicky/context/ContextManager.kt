package org.example.vicky.context

import com.aallam.openai.api.chat.ChatMessage
import org.example.vicky.agent.AgentMode
import org.example.vicky.tool.ToolRegistry

/**
 * 上下文管理抽象接口。覆盖会话历史存储、system prompt 构建、上下文压缩三大职责。
 *
 * 默认实现参见 root 模块的 `DefaultContextManager`，子类可自行实现以替换上下文管理策略。
 */
interface ContextManager {
    /** 获取/创建指定会话的历史列表（可变引用）。 */
    fun history(conversationId: String): MutableList<ChatMessage>

    /** 构建 system prompt。 */
    fun buildSystemPrompt(mode: AgentMode, tools: ToolRegistry): String

    /** 在 LLM 请求前确保 history 不超限（可能修改 history）。 */
    suspend fun ensureContextBudget(history: MutableList<ChatMessage>)

    /** 压缩旧的 tool call 轮次为摘要（可能修改 history）。 */
    fun compactOldToolRounds(history: MutableList<ChatMessage>)

    /** 清空指定会话。 */
    fun clear(conversationId: String)

    /** 按需裁剪指定会话消息到上限。 */
    fun trimIfNeeded(conversationId: String)
}
