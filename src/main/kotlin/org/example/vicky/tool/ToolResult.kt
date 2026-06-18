package org.example.vicky.tool

/**
 * 工具执行结果。
 * @property toAgent 喂回 agent 继续推理的 tool message 内容 (永远必填)。
 * @property userReply 可选，若非空则**立即**通过 MessageSink 发给 user。
 * @property endTurn 若为 true，Agent 在本步所有工具执行完后立即结束本轮思考，
 *                   不再请求下一轮 completion。
 */
data class ToolResult(
    val toAgent: String,
    val userReply: String? = null,
    val endTurn: Boolean = false,
)
