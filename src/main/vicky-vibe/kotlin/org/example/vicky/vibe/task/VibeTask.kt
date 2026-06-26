package org.example.vicky.vibe.task

import org.example.vicky.vibe.role.AgentRole

data class VibeTask(
    val id: String,
    val subject: String,
    val status: VibeTaskStatus = VibeTaskStatus.PENDING,
    val role: AgentRole? = null,
    val result: String? = null,
    val blockedBy: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
