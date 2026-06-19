package org.example.vicky.examples

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.channel.onebot.OneBot
import org.example.vicky.config.ConfigManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult
import org.example.vicky.tool.tool
import java.net.InetSocketAddress
import java.net.Socket

/** 一个最小的 echo 工具：把参数 text 既回给 agent，也实时回给 user。 */
private class EchoTool : Tool() {
    override val name = "echo"
    override val description = "Echo a piece of text back to the user."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to echo back.")
            }
        }
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val text = args["text"]?.jsonPrimitive?.content.orEmpty()
        return ToolResult(toAgent = "echoed: $text", userReply = text)
    }
}

/** 仅 admin 可调用：演示权限拦截。 */
private class ShutdownTool : Tool() {
    override val name = "shutdown"
    override val description = "Shut down the bot. Admin only."
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }
    override suspend fun execute(userId: String, args: JsonObject) =
        ToolResult(toAgent = "shutdown invoked", userReply = "Bye.")
}

/** 测试用：TCP connect 测网络延迟 (Windows 下比 ICMP 稳)。 */
private class PingTool : Tool() {
    override val name = "ping"
    override val description =
        "Measure network latency to a host via TCP connect. " + "Args: host (required), port (default 80), count (default 4), timeoutMs (default 3000)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("host") { put("type", "string"); put("description", "Target IP or hostname.") }
            putJsonObject("port") { put("type", "integer"); put("description", "TCP port, default 80.") }
            putJsonObject("count") { put("type", "integer"); put("description", "Attempts, default 4.") }
            putJsonObject("timeoutMs") {
                put("type", "integer"); put(
                "description", "Per-attempt timeout ms, default 3000."
            )
            }
        }
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("host")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val host = args["host"]?.jsonPrimitive?.content ?: return ToolResult(toAgent = "Error: missing 'host'.")
        val port = args["port"]?.jsonPrimitive?.int ?: 80
        val count = (args["count"]?.jsonPrimitive?.int ?: 4).coerceIn(1, 20)
        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.int ?: 3000

        val latencies = mutableListOf<Long>()
        var failures = 0
        repeat(count) {
            val ms = measureConnect(host, port, timeoutMs)
            if (ms >= 0) latencies += ms else failures++
        }

        val summary = if (latencies.isEmpty()) {
            "ping $host:$port -> all $count attempts failed (timeout/unreachable)."
        } else {
            "ping $host:$port -> ${latencies.size}/$count ok, " + "min=${latencies.min()}ms avg=${
                latencies.average().toLong()
            }ms max=${latencies.max()}ms" + if (failures > 0) " ($failures failed)" else ""
        }
        return ToolResult(toAgent = summary, userReply = "对 $host:$port 的 ping 测试已完成，结果如下：\n$summary")
    }

    private fun measureConnect(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            -1
        }
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

    agent.registerTool(ShutdownTool())
    agent.registerTool(PingTool())
    agent.registerTool(
        tool(
            name = "now",
            description = "Return the current local date-time.",
            parameters = buildJsonObject { put("type", "object") },
        ) { _, _ ->
            val now = java.time.LocalDateTime.now().toString()
            ToolResult(toAgent = now, userReply = "现在时间：$now")
        })

    println("Vicky console agent. Type 'quit' to exit.")
    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line == "quit") break
        if (line.isEmpty()) continue
        agent.receive(clearContextAfter = true,
            msg = InboundMessage(userId = "user1", content = line),
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