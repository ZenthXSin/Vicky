package org.example.vicky.io

sealed interface OutboundMessage {
    val conversationId: String
    val userId: String
    val groupId: String
    val content: String
    val type: String

    data class AgentReply(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
        override val type: String = "AgentReply",
    ) : OutboundMessage

    data class ToolReply(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
        val toolName: String,
        override val type: String = "ToolReply",
    ) : OutboundMessage

    data class Debug(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
        override val type: String = "Debug",
    ) : OutboundMessage

    data class Think(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        override val content: String,
        override val type: String = "Think",
    ) : OutboundMessage

    data class TokenUsage(
        override val conversationId: String,
        override val userId: String,
        override val groupId: String = "",
        val promptTokens: Int,
        val completionTokens: Int,
        val sessionTotalUsed: Int,
        override val content: String = "→llm ${promptTokens}tk  ←llm ${completionTokens}tk  累计${sessionTotalUsed}tk",
        override val type: String = "TokenUsage",
    ) : OutboundMessage
}
