package org.example.vicky.vibe.orchestrator

import org.example.vicky.agent.AgentConfig
import org.example.vicky.io.InboundMessage
import org.example.vicky.vibe.agent.VibeAgent
import org.example.vicky.vibe.agent.VibeAgentMode
import org.example.vicky.vibe.pipeline.Orchestrator
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.Pipeline
import org.example.vicky.vibe.pipeline.StageInput
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.role.AgentRole
import org.example.vicky.vibe.status.DefaultStatusPanel
import org.example.vicky.vibe.task.InMemoryTaskGraph
import org.example.vicky.vibe.task.TaskGraph

class VibeOrchestrator(
    private val baseConfig: AgentConfig,
    override val pipeline: Pipeline = Pipeline.default(),
    override val taskGraph: TaskGraph = InMemoryTaskGraph(),
    override val statusPanel: DefaultStatusPanel = DefaultStatusPanel(),
) : Orchestrator {

    override suspend fun execute(request: String): OrchestratorResult {
        val startTime = System.currentTimeMillis()
        val stageOutputs = mutableListOf<StageOutput>()

        statusPanel.init("Vibe Pipeline", pipeline.stages)

        for ((index, stage) in pipeline.stages.withIndex()) {
            statusPanel.notifyStageStart(index)

            val input = StageInput(
                request = request,
                previousResults = StagePromptBuilder.buildPreviousResults(stageOutputs),
                tasks = StagePromptBuilder.buildTaskGraphSnapshot(taskGraph),
            )

            val output = try {
                callStage(stage.role, input, stageOutputs.toList())
            } catch (e: Exception) {
                statusPanel.notifyError(e.message ?: "unknown", stage)
                StageOutput(summary = "Error: ${e.message}", output = e.stackTraceToString())
            }

            stageOutputs.add(output)
            statusPanel.notifyStageComplete(index, output)

            for (proposal in output.tasks) {
                val task = taskGraph.create(
                    subject = proposal.subject,
                    role = AgentRole.fromId(proposal.role ?: stage.role.id),
                    blockedBy = proposal.blockedBy.toSet(),
                )
                statusPanel.notifyTaskUpdate(task)
            }

            if (stage.role == AgentRole.REVIEW && output.pass == false) break
        }

        val elapsed = System.currentTimeMillis() - startTime
        val result = OrchestratorResult(
            stages = stageOutputs,
            tasks = taskGraph.all(),
            elapsed = elapsed,
            success = stageOutputs.lastOrNull()?.pass != false,
        )
        statusPanel.notifyPipelineComplete(result)
        return result
    }

    private suspend fun callStage(
        role: AgentRole,
        input: StageInput,
        previousOutputs: List<StageOutput>,
    ): StageOutput {
        val stage = pipeline.stages.first { it.role == role }
        val agentMd = StagePromptBuilder.buildSystemPrompt(role)
        val config = baseConfig.copy(
            agentMd = agentMd,
            maxSteps = Int.MAX_VALUE, // 不限制轮次，直到任务完成
            mode = VibeAgentMode,
            id = "vibe-${role.id}",
            name = "vibe-${role.label}",
        )
        val agent = VibeAgent(config)

        val userMessage = StagePromptBuilder.buildUserMessage(role, input, previousOutputs)
        agent.receive(
            msg = InboundMessage(userId = "vibe-orchestrator", content = userMessage),
            clearContextAfter = true,
        )

        val reply = agent.lastAssistantReply ?: ""
        return StagePromptBuilder.parseStageOutput(reply)
    }
}
