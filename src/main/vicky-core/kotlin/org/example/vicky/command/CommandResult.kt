package org.example.vicky.command

/**
 * 命令执行结果。
 *
 * @property reply 回复给用户的消息（由调度器通过 sink 发送）。
 * @property passthrough 若为 true，命令处理完毕后仍把原始输入送给 Agent。
 */
data class CommandResult(
    val reply: String? = null,
    val passthrough: Boolean = false,
)
