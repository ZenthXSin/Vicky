package org.example.vicky.tool

import org.example.vicky.context.ContextManager

/**
 * 工具执行时的运行时上下文，供 (尤其是内置) 工具访问框架状态。
 *
 * @property userId 调用者。
 * @property conversationId 会话 key (群聊场景下与 userId 不同)。
 * @property groupId 群聊id（如果有）
 * @property contextManager 上下文管理器，可用于清空上下文等操作。
 * @property tools 当前工具注册表。
 * @property buffer 消息缓冲区 (仅 OneBot 等渠道提供，控制台场景为 null)。
 */
class ToolContext(
    val userId: String,
    val conversationId: String,
    val groupId: String = "",
    val contextManager: ContextManager,
    val tools: ToolRegistry,
    val buffer: org.example.vicky.buffer.MessageBuffer? = null,
)
