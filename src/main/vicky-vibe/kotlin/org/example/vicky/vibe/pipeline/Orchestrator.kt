package org.example.vicky.vibe.pipeline

import org.example.vicky.vibe.status.StatusPanel
import org.example.vicky.vibe.task.TaskGraph
import org.example.vicky.vibe.task.VibeTask

data class OrchestratorResult(
    val stages: List<StageOutput>,
    val tasks: List<VibeTask>,
    val elapsed: Long,
    val success: Boolean,
)

interface Orchestrator {
    val pipeline: Pipeline
    val taskGraph: TaskGraph
    val statusPanel: StatusPanel

    suspend fun execute(request: String): OrchestratorResult
}
