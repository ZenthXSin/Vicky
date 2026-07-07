package org.example.vicky.examples

import kotlinx.coroutines.runBlocking
import org.example.vicky.config.ConfigManager
import org.example.vicky.memory.MemoryRuntime
import org.example.vicky.tool.builtin.BuiltinTools
import org.example.vicky.vibe.orchestrator.VibeOrchestrator
import org.example.vicky.vibe.pipeline.OrchestratorResult
import org.example.vicky.vibe.status.DefaultStatusPanel

fun vibeCodeCliMain(args: Array<String>) = runBlocking {
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
    val memoryConfig = ConfigManager.toMemoryConfig(loadResult.config)
    val memoryRuntime = MemoryRuntime.create(memoryConfig, config)
    memoryRuntime.startWarmup(
        memoryConfig = memoryConfig,
        onFailure = { e -> println("[Vicky] 记忆系统初始化失败: ${e.message}") },
    )

    val renderer = VibeCliRenderer(model = loadResult.config.model)

    fun createStatusPanel() = DefaultStatusPanel().also { it.addObserver(renderer) }

    fun createOrchestrator(statusPanel: DefaultStatusPanel) = VibeOrchestrator(
        baseConfig = config,
        statusPanel = statusPanel,
        conversationId = "vibe-code-cli",
        resetContextEachTurn = false,
    ) { agent ->
        agent.sink = renderer.sink
        BuiltinTools.all(
            baseDir = java.io.File(System.getProperty("user.dir")),
            memoryStore = memoryRuntime.memoryStore,
            fileIndexService = memoryRuntime.fileIndexService,
            distillationScheduler = memoryRuntime.distillationScheduler,
            agentConfig = config,
        ).forEach { agent.tools.register(it) }
    }

    suspend fun runTurn(orchestrator: VibeOrchestrator, request: String) {
        val result = orchestrator.execute(request)
        printResult(result)
    }

    try {
        if (args.isNotEmpty()) {
            val statusPanel = createStatusPanel()
            runTurn(createOrchestrator(statusPanel), args.joinToString(" "))
            return@runBlocking
        }

        val statusPanel = createStatusPanel()
        val orchestrator = createOrchestrator(statusPanel)
        renderer.printHeader()
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
    } finally {
        memoryRuntime.close()
    }
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
        println("  no stages"); return
    }
    snapshot.stages.forEachIndexed { i, s ->
        println("  ${i + 1}. ${s.role.label} ${s.status} ${s.elapsed}ms${s.summary?.let { " - $it" } ?: ""}")
    }
}
