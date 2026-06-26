package org.example.vicky.vibe.status

import org.example.vicky.vibe.role.AgentRole
import org.example.vicky.vibe.task.VibeTask
import org.example.vicky.vibe.task.VibeTaskStatus

data class StatusSnapshot(
    val title: String,
    val stages: List<StageSnapshot>,
    val tasks: List<VibeTask>,
    val elapsed: Long,
)

data class StageSnapshot(
    val role: AgentRole,
    val status: VibeTaskStatus,
    val summary: String?,
    val elapsed: Long,
)
