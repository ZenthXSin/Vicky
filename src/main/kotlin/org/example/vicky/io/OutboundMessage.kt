package org.example.vicky.io

sealed interface OutboundMessage {
    val conversationId: String
    val userId: String
    val groupId: String
    val content: String

    /** Agent 自身的对话回复 (Mode VERBOSE 才会推出)。 */
    data class AgentReply(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
    ) : OutboundMessage

    /** 来自工具的、面向用户的回复。 */
    data class ToolReply(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
        val toolName: String,
    ) : OutboundMessage

    /** 框架运行日志 (config.debug 打开时推出)，面向开发者而非用户。 */
    data class Debug(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
    ) : OutboundMessage

    /** Agent 每轮的中间思考文本 (config.think 打开时推出)。 */
    data class Think(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
    ) : OutboundMessage
}
