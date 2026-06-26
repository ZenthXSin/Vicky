package org.example.vicky.command

import org.example.vicky.io.MessageSink

/**
 * 命令执行时的运行时上下文。
 *
 * @property userId 调用者 ID。
 * @property conversationId 会话 key（群聊场景下与 userId 不同）。
 * @property groupId 群聊 ID（私聊时为空字符串）。
 * @property sink 消息出口，命令可通过 sink 主动推送消息给用户。
 */
class CommandContext(
    val userId: String,
    val conversationId: String,
    val groupId: String = "",
    val sink: MessageSink,
)
