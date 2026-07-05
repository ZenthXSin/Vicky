package org.example.vicky.vibe.orchestrator

import org.example.vicky.agent.AgentConfig
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.vibe.agent.VibeAgent
import org.example.vicky.vibe.agent.VibeAgentMode
import org.example.vicky.vibe.pipeline.Orchestrator
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.Pipeline
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.status.DefaultStatusPanel
import org.example.vicky.vibe.task.InMemoryTaskGraph
import org.example.vicky.vibe.task.TaskGraph
import org.example.vicky.vibe.turn.VibeTurnRequest
import org.example.vicky.vibe.turn.VibeTurnResult
import org.example.vicky.vibe.turn.VibeTurnRunner

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

    fun clearContext() {
        contextManager.clear(conversationId)
    }

    fun createInboundMessage(content: String, userId: String = "vibe-orchestrator", groupId: String = ""): InboundMessage =
        InboundMessage(userId = userId, content = content, conversationId = conversationId, groupId = groupId)

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
        statusPanel.init("Vibe", pipeline.stages)
        statusPanel.notifyStageStart(0)

        val result = runTurn(request)
        val output = toStageOutput(result)
        statusPanel.notifyStageComplete(0, output)

        val elapsed = System.currentTimeMillis() - startTime
        val orchestratorResult = OrchestratorResult(
            stages = listOf(output),
            tasks = taskGraph.all(),
            elapsed = elapsed,
            success = result.success,
        )
        statusPanel.notifyPipelineComplete(orchestratorResult)
        return orchestratorResult
    }
}
