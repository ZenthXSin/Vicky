package org.example.vicky.vibe.pipeline

import org.example.vicky.vibe.role.AgentRole

data class PipelineStage(
    val role: AgentRole,
    val prompt: String = "",
    val tools: Set<String> = emptySet(),
)
