package org.example.vicky.io

data class InboundMessage(
    val userId: String,
    val content: String,
    /** Conversation key — defaults to userId. Override for group chats etc. */
    val conversationId: String = userId,
)
