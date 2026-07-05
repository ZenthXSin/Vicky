package org.example.vicky.examples

import kotlinx.coroutines.runBlocking
import org.example.vicky.config.ConfigManager
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.builtin.BuiltinTools
import org.example.vicky.vibe.orchestrator.VibeOrchestrator
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.pipeline.PipelineStage
import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.status.DefaultStatusPanel
import org.example.vicky.vibe.status.StatusObserver
import org.example.vicky.vibe.task.VibeTask

fun main(args: Array<String>) = runBlocking {
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))

    val loadResult = ConfigManager.loadOrCreate()
    if (loadResult.firstRun) {
        println("首次运行，已生成配置文件: ${ConfigManager.getConfigDir().absolutePath}")
        println("请修改 config.json 和 agentMd 文件后重新运行。")
        return@runBlocking
    }

    val config = ConfigManager.toAgentConfig(loadResult.config, loadResult.agentMd).copy(
        builtinTools = true,
        maxSteps = maxOf(loadResult.config.maxSteps, 16),
        debug = false,
        think = true,
    )

    fun createStatusPanel() = DefaultStatusPanel()

    fun createOrchestrator(statusPanel: DefaultStatusPanel) = VibeOrchestrator(
        baseConfig = config,
        statusPanel = statusPanel,
        conversationId = "vibe-code-cli",
        resetContextEachTurn = false,
    ) { agent ->
        agent.sink = createCliSink()
        BuiltinTools.all(
            baseDir = java.io.File(System.getProperty("user.dir")),
            agentConfig = config,
        ).forEach { agent.tools.register(it) }
    }

    suspend fun runTurn(orchestrator: VibeOrchestrator, request: String) {
        println()
        println("● 我先处理这个请求，再把进展实时告诉你。")
        val result = orchestrator.execute(request)
        printResult(result)
    }

    if (args.isNotEmpty()) {
        val statusPanel = createStatusPanel()
        runTurn(createOrchestrator(statusPanel), args.joinToString(" "))
        return@runBlocking
    }

    val statusPanel = createStatusPanel().also(::attachStatusObserver)
    val orchestrator = createOrchestrator(statusPanel)
    printWelcome()

    val buffer = mutableListOf<String>()
    while (true) {
        print(if (buffer.isEmpty()) "claude> " else "... ")
        val line = readlnOrNull() ?: break
        val trimmed = line.trim()
        when {
            buffer.isEmpty() && trimmed in setOf("/exit", "exit", "quit", "q") -> break
            buffer.isEmpty() && trimmed == "/help" -> printHelp()
            buffer.isEmpty() && trimmed == "/clear" -> {
                orchestrator.clearContext()
                println("● 已清空当前会话上下文。")
            }
            buffer.isEmpty() && trimmed == "/status" -> printStatus(statusPanel.snapshot())
            buffer.isEmpty() && trimmed.isEmpty() -> Unit
            trimmed.isEmpty() -> {
                val request = buffer.joinToString("\n").trim()
                buffer.clear()
                if (request.isNotBlank()) runTurn(orchestrator, request)
            }
            else -> buffer += line
        }
    }

    val tail = buffer.joinToString("\n").trim()
    if (tail.isNotBlank()) runTurn(orchestrator, tail)
}

private fun printWelcome() {
    println("Vibe Code CLI")
    println("输入多行内容后用空行发送。输入 /help 查看命令。")
}

private fun printHelp() {
    println("/help   显示帮助")
    println("/clear  清空会话上下文")
    println("/status 查看当前状态")
    println("/exit   退出")
}

private fun printResult(result: OrchestratorResult) {
    val stage = result.stages.lastOrNull()
    val summary = stage?.summary?.takeIf { it.isNotBlank() }
    val output = stage?.output?.trim().orEmpty()

    println()
    if (summary != null) println("● $summary")
    if (output.isNotBlank()) println(output)
    println()
    println("· success=${result.success} tasks=${result.tasks.size} elapsed=${result.elapsed}ms")
}

private fun printStatus(snapshot: org.example.vicky.vibe.status.StatusSnapshot) {
    println("· ${snapshot.title} (${snapshot.elapsed}ms)")
    if (snapshot.stages.isEmpty()) {
        println("  ⎿  无阶段")
        return
    }
    snapshot.stages.forEachIndexed { index, stage ->
        println("  ${index + 1}. ${stage.role.label} ${stage.status} ${stage.elapsed}ms${stage.summary?.let { " - $it" } ?: ""}")
    }
}

private fun attachStatusObserver(statusPanel: DefaultStatusPanel) {
    statusPanel.addObserver(object : StatusObserver {
        override fun onStageStart(stage: PipelineStage, index: Int, total: Int) {
            println("● 我先处理 ${stage.role.label}，再继续往下推进。")
        }

        override fun onStageComplete(stage: PipelineStage, output: StageOutput, index: Int, total: Int) {
            println("  ⎿  ${output.summary}")
        }

        override fun onTaskUpdate(task: VibeTask) {
            println("· ${task.subject} (${task.status})")
        }

        override fun onPipelineComplete(result: OrchestratorResult) = Unit

        override fun onError(error: String, stage: PipelineStage?) {
            println("  ⎿  error: ${stage?.role?.label ?: "vibe"}: $error")
        }
    })
}

private fun createCliSink() = org.example.vicky.io.MessageSink { out ->
    when (out) {
        is OutboundMessage.AgentReply -> if (out.content.isNotBlank()) println(out.content)
        is OutboundMessage.ToolReply -> println("  Read tool output from ${out.toolName}")
        is OutboundMessage.Debug -> println(formatDebugLine(out.content))
        is OutboundMessage.Think -> println(formatThinkLine(out.content))
        is OutboundMessage.TokenUsage -> println("  ⎿  ${out.content}")
    }
}

private fun formatDebugLine(content: String): String {
    return when {
        content.startsWith("[task:") -> "· ${content.removePrefix("[").removeSuffix("]")}"
        content.contains("requesting completion") -> "● 我先查看现有上下文，再决定下一步。"
        content.contains("parsed") && content.contains("inline tool calls") -> "  Read inline tool calls"
        content.contains("maxSteps") -> "  ⎿  已达到步数上限，准备收尾总结。"
        content.contains("vibe failed") -> "  ⎿  $content"
        else -> "  ⎿  $content"
    }
}

private fun formatThinkLine(content: String): String {
    return when {
        content.startsWith("Use Tool: ") -> "  Read tool: ${content.removePrefix("Use Tool: ")}"
        content.isBlank() -> ""
        else -> "● ${content.lines().first().trim()}"
    }
}
