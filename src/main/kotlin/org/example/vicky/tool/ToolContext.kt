package org.example.vicky.tool

import org.example.vicky.context.ConversationStore

/**
 * 工具执行时的运行时上下文，供 (尤其是内置) 工具访问框架状态。
 *
 * @property userId 调用者。
 * @property conversationId 会话 key (群聊场景下与 userId 不同)。
 * @property store 会话历史存储，可用于清空上下文等操作。
 * @property tools 当前工具注册表。
 */
class ToolContext(
    val userId: String,
    val conversationId: String,
    val store: ConversationStore,
    val tools: ToolRegistry,
)
