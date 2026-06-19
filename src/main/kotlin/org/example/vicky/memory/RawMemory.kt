package org.example.vicky.memory

/**
 * 原始记忆。保存每轮对话的完整内容（去除工具调用输出）。
 */
data class RawMemory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userId: String,
    val conversationId: String,
    val role: String,           // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val turnIndex: Int = 0,
    val distilled: Boolean = false,
)
