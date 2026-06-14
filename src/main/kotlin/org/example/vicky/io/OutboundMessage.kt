package org.example.vicky.io

sealed interface OutboundMessage {
    val conversationId: String
    val userId: String
    val content: String

    /** Agent 自身的对话回复 (Mode VERBOSE 才会推出)。 */
    data class AgentReply(
        override val conversationId: String,
        override val userId: String,
        override val content: String,
    ) : OutboundMessage

    /** 来自工具的、面向用户的回复。 */
    data class ToolReply(
        override val conversationId: String,
        override val userId: String,
        override val content: String,
        val toolName: String,
    ) : OutboundMessage
}
