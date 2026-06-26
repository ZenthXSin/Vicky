package org.example.vicky.examples

import kotlinx.coroutines.runBlocking
import org.example.vicky.annotations.ToolGroup
import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.channel.onebot.OneBot
import org.example.vicky.command.CommandContext
import org.example.vicky.command.CommandRegistry
import org.example.vicky.config.ConfigManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolResult

@ToolGroup(name = "Vicky")
object ConsoleTools {

    @VickyTool(name = "echo", description = "Echo a piece of text back to the user.")
    fun echo(
        @ToolParam(description = "Text to echo back.") text: String,
    ): ToolResult = ToolResult(toAgent = "echoed: $text", userReply = text)

    @VickyTool(name = "shutdown", description = "Shut down the bot. Admin only.")
    fun shutdown(): ToolResult =
        ToolResult(toAgent = "shutdown invoked", userReply = "Bye.")

    @VickyTool(name = "now", description = "Return the current local date-time.")
    fun now(): ToolResult {
        val now = java.time.LocalDateTime.now().toString()
        return ToolResult(toAgent = now, userReply = "现在时间：$now")
    }
}

fun main() = runBlocking {
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))

    val result = ConfigManager.loadOrCreate()
    if (result.firstRun) {
        println("首次运行，已生成配置文件: ${ConfigManager.getConfigDir().absolutePath}")
        println("请修改 config.json 和 agentMd 文件后重新运行。")
        return@runBlocking
    }

    val agentConfig = ConfigManager.toAgentConfig(result.config, result.agentMd)
    val memoryConfig = ConfigManager.toMemoryConfig(result.config)
    val oneBotConfig = result.config.oneBot

    println("[Vicky] 配置已加载: ${ConfigManager.getConfigDir().absolutePath}")

    org.example.vicky.skill.SkillLoader.load(
        java.io.File(ConfigManager.getConfigDir(), "skills"),
        result.config.skillStates,
    )

    val oneBot = OneBot(agentConfig, memoryConfig, oneBotConfig.url, oneBotConfig.token)

    oneBot.connect()

    oneBotConfig.adminList.forEach { oneBot.adminList.add(it) }
    oneBotConfig.groupWhitelist.forEach { oneBot.groupWhitelist.add(it) }
    oneBotConfig.userWhitelist.forEach { oneBot.userWhitelist.add(it) }

    var currentConfig = result.config

    oneBot.onGroupWhitelistChanged = { latest ->
        currentConfig = currentConfig.copy(
            oneBot = currentConfig.oneBot.copy(groupWhitelist = latest.toList())
        )
        ConfigManager.save(currentConfig)
    }

    val agent = oneBot.agent
    val commandRegistry = oneBot.commandRegistry

    // ─── 控制台命令注册 ───
    // TODO: 在此注册控制台专用命令

    val consoleSink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReply -> println("[agent] ${out.content}")
            is OutboundMessage.ToolReply -> println("[tool:${out.toolName}] ${out.content}")
            is OutboundMessage.Debug -> println("[debug] ${out.content}")
            is OutboundMessage.Think -> println("[think] ${out.content}")
        }
    }

    println("Vicky console agent. Type 'quit' to exit. Commands start with '/'.")

    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line == "quit") break
        if (line.isEmpty()) continue

        // 命令分发：/ 开头走命令系统，其余走 Agent
        val ctx = CommandContext(
            userId = "488254306",
            conversationId = "488254306",
            sink = consoleSink,
        )
        val cmdResult = commandRegistry.dispatch(ctx, line)

        if (cmdResult != null) {
            // 命令已处理
            cmdResult.reply?.let { println(it) }
            // passthrough = true 时仍把原始输入送给 Agent
            if (!cmdResult.passthrough) continue
        }

        agent.receive(
            clearContextAfter = true,
            msg = InboundMessage(userId = "488254306", content = line),
            replySink = consoleSink,
        )
    }
}
