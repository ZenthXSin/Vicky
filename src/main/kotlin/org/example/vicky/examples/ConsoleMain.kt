package org.example.vicky.examples

import kotlinx.coroutines.runBlocking
import org.example.vicky.annotations.ToolGroup
import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.channel.onebot.OneBot
import org.example.vicky.config.ConfigManager
import org.example.vicky.generated.ToolRegistry
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolResult
import java.net.InetSocketAddress
import java.net.Socket

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
    val oneBotConfig = result.config.oneBot

    println("[Vicky] 配置已加载: ${ConfigManager.getConfigDir().absolutePath}")

    val oneBot = OneBot(agentConfig, oneBotConfig.url, oneBotConfig.token)

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

    //ToolRegistry.tools("Vicky").forEach { agent.registerTool(it) }
    //ToolRegistry.tools("mindustry").forEach { agent.registerTool(it) }

    println("Vicky console agent. Type 'quit' to exit.")
    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line == "quit") break
        if (line.isEmpty()) continue
        agent.receive(clearContextAfter = true,
            msg = InboundMessage(userId = "488254306", content = line),
            replySink = {
            when (it) {
                is OutboundMessage.AgentReply -> println("[agent] ${it.content}")
                is OutboundMessage.ToolReply -> println("[tool:${it.toolName}] ${it.content}")
                is OutboundMessage.Debug -> println("[debug] ${it.content}")
                is OutboundMessage.Think -> println("[think] ${it.content}")
            }
        })
    }
}
