package org.example.vicky.vibe.status

import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.PipelineStage
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.task.VibeTask
import org.example.vicky.vibe.task.VibeTaskStatus
import java.util.concurrent.CopyOnWriteArrayList

interface StatusPanel {
    fun addObserver(observer: StatusObserver)
    fun removeObserver(observer: StatusObserver)
    fun snapshot(): StatusSnapshot
}

class DefaultStatusPanel : StatusPanel {
    private val observers = CopyOnWriteArrayList<StatusObserver>()

    // 内部状态
    private val stageStates = mutableListOf<StageState>()
    private var pipelineStartTime: Long = 0L
    private var title: String = "Pipeline"

    private data class StageState(
        val stage: PipelineStage,
        val index: Int,
        var status: VibeTaskStatus = VibeTaskStatus.PENDING,
        var summary: String? = null,
        var startTime: Long = 0L,
        var endTime: Long = 0L,
    )

    override fun addObserver(observer: StatusObserver) {
        observers.add(observer)
    }

    override fun removeObserver(observer: StatusObserver) {
        observers.remove(observer)
    }

    override fun snapshot(): StatusSnapshot {
        val elapsed = if (pipelineStartTime > 0) System.currentTimeMillis() - pipelineStartTime else 0L
        return StatusSnapshot(
            title = title,
            stages = stageStates.map { s ->
                StageSnapshot(
                    role = s.stage.role,
                    status = s.status,
                    summary = s.summary,
                    elapsed = if (s.endTime > 0) s.endTime - s.startTime
                    else if (s.startTime > 0) System.currentTimeMillis() - s.startTime
                    else 0L,
                )
            },
            tasks = emptyList(), // 由 Orchestrator 注入
            elapsed = elapsed,
        )
    }

    // ─── 供 Orchestrator 调用的状态更新方法 ───

    fun init(title: String, stages: List<PipelineStage>) {
        this.title = title
        this.pipelineStartTime = System.currentTimeMillis()
        this.stageStates.clear()
        stages.forEachIndexed { i, stage ->
            stageStates.add(StageState(stage = stage, index = i))
        }
    }

    fun notifyStageStart(index: Int) {
        stageStates.getOrNull(index)?.let { state ->
            state.status = VibeTaskStatus.IN_PROGRESS
            state.startTime = System.currentTimeMillis()
            observers.forEach { it.onStageStart(state.stage, index, stageStates.size) }
        }
    }

    fun notifyStageComplete(index: Int, output: StageOutput) {
        stageStates.getOrNull(index)?.let { state ->
            state.status = VibeTaskStatus.COMPLETED
            state.summary = output.summary
            state.endTime = System.currentTimeMillis()
            observers.forEach { it.onStageComplete(state.stage, output, index, stageStates.size) }
        }
    }

    fun notifyTaskUpdate(task: VibeTask) {
        observers.forEach { it.onTaskUpdate(task) }
    }

    fun notifyPipelineComplete(result: OrchestratorResult) {
        observers.forEach { it.onPipelineComplete(result) }
    }

    fun notifyError(error: String, stage: PipelineStage?) {
        observers.forEach { it.onError(error, stage) }
    }
}
