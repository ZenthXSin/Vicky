package org.example.vicky.vibe.orchestrator

import org.example.vicky.agent.AgentConfig
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.vibe.agent.VibeAgent
import org.example.vicky.vibe.agent.VibeAgentMode
import org.example.vicky.tool.Tool
import org.example.vicky.vibe.pipeline.Orchestrator
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.Pipeline
import org.example.vicky.vibe.pipeline.PipelineStage
import org.example.vicky.vibe.pipeline.StageInput
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.pipeline.StageTaskProposal
import org.example.vicky.vibe.role.AgentRole
import org.example.vicky.vibe.status.DefaultStatusPanel
import org.example.vicky.vibe.task.InMemoryTaskGraph
import org.example.vicky.vibe.task.TaskGraph
import org.example.vicky.vibe.turn.VibeTurnRequest
import org.example.vicky.vibe.turn.VibeTurnResult
import org.example.vicky.vibe.turn.VibeTurnRunner
import java.util.concurrent.atomic.AtomicLong

class VibeOrchestrator(
    val baseConfig: AgentConfig,
    override val pipeline: Pipeline = Pipeline.default(),
    override val taskGraph: TaskGraph = InMemoryTaskGraph(),
    override val statusPanel: DefaultStatusPanel = DefaultStatusPanel(),
    val conversationId: String = "vibe-orchestrator",
    val resetContextEachTurn: Boolean = true,
    private val configureAgent: (VibeAgent) -> Unit = {},
) : Orchestrator {

    val config = baseConfig.copy(
        agentMd = VibeSystemPromptBuilder.build(),
        maxSteps = if (baseConfig.maxSteps == Int.MAX_VALUE) baseConfig.maxSteps else maxOf(baseConfig.maxSteps, 16),
        mode = VibeAgentMode,
        id = "vibe-runner",
        name = "vibe-runner",
    )
    val agent: VibeAgent by lazy {
        VibeAgent(config).also(configureAgent)
    }
    val tools: ToolRegistry get() = agent.tools
    val contextManager: ContextManager get() = agent.vibeContextManager
    val authorizer: ToolAuthorizer get() = agent.vibeAuthorizer
    val sink: MessageSink get() = agent.vibeSink
    val turnRunner: VibeTurnRunner = VibeTurnRunner()
    private val runCounter = AtomicLong(0)
    private val taskIdsBySubject = linkedMapOf<String, String>()

    fun clearContext() {
        contextManager.clear("*")
        taskIdsBySubject.clear()
    }

    fun createInboundMessage(
        content: String,
        userId: String = "vibe-orchestrator",
        groupId: String = "",
        messageConversationId: String = conversationId,
    ): InboundMessage =
        InboundMessage(userId = userId, content = content, conversationId = messageConversationId, groupId = groupId)

    fun createTurnRequest(inbound: InboundMessage = createInboundMessage("")): VibeTurnRequest =
        VibeTurnRequest(
            config = config,
            inbound = inbound,
            tools = tools,
            contextManager = contextManager,
            authorizer = authorizer,
            sink = sink,
            taskGraph = taskGraph,
            resetContext = resetContextEachTurn,
        )

    suspend fun runTurn(request: String, userId: String = "vibe-orchestrator", groupId: String = ""): VibeTurnResult =
        runTurn(createInboundMessage(request, userId, groupId))

    suspend fun runTurn(inbound: InboundMessage): VibeTurnResult =
        turnRunner.run(createTurnRequest(inbound))

    fun toStageOutput(result: VibeTurnResult): StageOutput = VibeResultAdapter.toStageOutput(result)

    override suspend fun execute(request: String): OrchestratorResult {
        val startTime = System.currentTimeMillis()
        val runId = runCounter.incrementAndGet()
        val outputs = mutableListOf<StageOutput>()
        var success = true

        taskGraph.clear()
        taskIdsBySubject.clear()
        statusPanel.init("Vibe", pipeline.stages)

        for ((index, stage) in pipeline.stages.withIndex()) {
            statusPanel.notifyStageStart(index)
            val stageResult = runStage(runId, index, stage, request, outputs)
            outputs += stageResult.output
            applyTaskProposals(stageResult.output.tasks)
            statusPanel.notifyStageComplete(index, stageResult.output)
            success = success && stageResult.turn.success
            stageResult.output.pass?.let { success = success && it }
            if (!stageResult.turn.success) break
        }

        val elapsed = System.currentTimeMillis() - startTime
        val reviewPass = outputs.lastOrNull { it.pass != null }?.pass
        val orchestratorResult = OrchestratorResult(
            stages = outputs,
            tasks = taskGraph.all(),
            elapsed = elapsed,
            success = reviewPass ?: success,
        )
        statusPanel.notifyPipelineComplete(orchestratorResult)
        return orchestratorResult
    }

    private suspend fun runStage(
        runId: Long,
        index: Int,
        stage: PipelineStage,
        request: String,
        previousOutputs: List<StageOutput>,
    ): StageRunResult {
        val input = StageInput(
            request = request,
            previousResults = StagePromptBuilder.buildPreviousResults(previousOutputs),
            tasks = StagePromptBuilder.buildTaskGraphSnapshot(taskGraph),
        )
        val userMessage = StagePromptBuilder.buildUserMessage(stage.role, input, previousOutputs)
        val stageConfig = config.copy(
            agentMd = buildStageSystemPrompt(stage),
            id = "vibe-${stage.role.id}-runner",
            name = "vibe-${stage.role.id}-runner",
        )
        val inbound = createInboundMessage(
            content = userMessage,
            messageConversationId = stageConversationId(runId, stage),
        )
        val turn = turnRunner.run(
            VibeTurnRequest(
                config = stageConfig,
                inbound = inbound,
                tools = filterToolsForStage(stage),
                contextManager = contextManager,
                authorizer = authorizer,
                sink = sink,
                taskGraph = taskGraph,
                resetContext = resetContextEachTurn,
            )
        )
        val output = turn.assistantReply
            ?.let { StagePromptBuilder.parseStageOutput(it) }
            ?: toStageOutput(turn)
        return StageRunResult(turn = turn, output = output)
    }

    private fun buildStageSystemPrompt(stage: PipelineStage): String = buildString {
        append(StagePromptBuilder.buildSystemPrompt(stage.role))
        if (stage.prompt.isNotBlank()) {
            append("\n\n---\n\n# Stage-specific instruction\n")
            append(stage.prompt)
        }
    }

    private fun applyTaskProposals(tasks: List<StageTaskProposal>) {
        for (proposal in tasks) {
            if (proposal.subject.isBlank() || taskIdsBySubject.containsKey(proposal.subject)) continue
            val blockedByIds = proposal.blockedBy.mapNotNull { taskIdsBySubject[it] }.toSet()
            val task = taskGraph.create(proposal.subject, proposal.role?.let { AgentRole.fromId(it) }, blockedByIds)
            taskIdsBySubject[proposal.subject] = task.id
            statusPanel.notifyTaskUpdate(task)
        }
    }

    private fun filterToolsForStage(stage: PipelineStage): ToolRegistry {
        val allowedNames = stage.tools.ifEmpty { defaultToolNamesFor(stage.role) }
        if (allowedNames == ALL_TOOLS) return tools
        return ToolRegistry().also { registry ->
            tools.snapshot()
                .filter { it.name in allowedNames }
                .forEach { registry.register(it) }
        }
    }

    private fun defaultToolNamesFor(role: AgentRole): Set<String> = when (role) {
        AgentRole.GENERAL, AgentRole.PLANNING -> emptySet()
        AgentRole.INVESTIGATION, AgentRole.REVIEW -> readOnlyToolNames()
        AgentRole.WRITING -> ALL_TOOLS
    }

    private fun readOnlyToolNames(): Set<String> = tools.snapshot()
        .map(Tool::name)
        .filter { name ->
            val normalized = name.lowercase()
            READ_ONLY_TOOL_MARKERS.any { marker -> marker in normalized }
        }
        .toSet()

    private fun stageConversationId(runId: Long, stage: PipelineStage): String =
        "$conversationId/run-$runId/${stage.role.id}"

    private data class StageRunResult(
        val turn: VibeTurnResult,
        val output: StageOutput,
    )

    private companion object {
        val ALL_TOOLS = setOf("*")
        val READ_ONLY_TOOL_MARKERS = listOf("read", "list", "search", "grep", "glob", "status", "get", "view", "info")
    }
}
