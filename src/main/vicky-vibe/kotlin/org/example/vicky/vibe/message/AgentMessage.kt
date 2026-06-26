package org.example.vicky.vibe.message

import org.example.vicky.vibe.role.AgentRole

data class AgentMessage(
    val from: AgentRole,
    val to: AgentRole,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val taskId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
