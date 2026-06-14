package org.example.vicky.tool

/**
 * 工具执行结果。
 * @property toAgent 喂回 agent 继续推理的 tool message 内容 (永远必填)。
 * @property userReply 可选，若非空则**立即**通过 MessageSink 发给 user。
 */
data class ToolResult(
    val toAgent: String,
    val userReply: String? = null,
)
