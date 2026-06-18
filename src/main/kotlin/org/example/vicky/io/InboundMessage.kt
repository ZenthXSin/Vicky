package org.example.vicky.io

data class InboundMessage(
    val userId: String,
    val content: String,
    /** Conversation key — defaults to userId. Override for group chats etc. */
    val conversationId: String = userId,
    /** 群号 (群消息时填写，私聊时为空字符串)。 */
    val groupId: String = "",
)
