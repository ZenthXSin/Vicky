package org.example.vicky.vibe.status

import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.PipelineStage
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.task.VibeTask

interface StatusObserver {
    fun onStageStart(stage: PipelineStage, index: Int, total: Int)
    fun onStageComplete(stage: PipelineStage, output: StageOutput, index: Int, total: Int)
    fun onTaskUpdate(task: VibeTask)
    fun onPipelineComplete(result: OrchestratorResult)
    fun onError(error: String, stage: PipelineStage?)
}
