package org.example.vicky.memory

/**
 * 蒸馏记忆。从原始对话中提取的关键信息。
 */
data class Memory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val summary: String = content,
    val tags: Set<String> = emptySet(),
    val userId: String? = null,
    val source: String = "learned",   // "user_stated" | "learned"
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val distilledAt: Long = System.currentTimeMillis(),
)
