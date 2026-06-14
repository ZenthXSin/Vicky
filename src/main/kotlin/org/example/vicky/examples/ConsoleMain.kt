package org.example.vicky.examples

import com.aallam.openai.api.model.ModelId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.agent.Agent
import org.example.vicky.agent.AgentConfig
import org.example.vicky.agent.AgentMode
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolAuthorizer
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
        "Measure network latency to a host via TCP connect. " +
            "Args: host (required), port (default 80), count (default 4), timeoutMs (default 3000)."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("host") { put("type", "string"); put("description", "Target IP or hostname.") }
            putJsonObject("port") { put("type", "integer"); put("description", "TCP port, default 80.") }
            putJsonObject("count") { put("type", "integer"); put("description", "Attempts, default 4.") }
            putJsonObject("timeoutMs") { put("type", "integer"); put("description", "Per-attempt timeout ms, default 3000.") }
        }
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("host")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val host = args["host"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'host'.")
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
            "ping $host:$port -> ${latencies.size}/$count ok, " +
                "min=${latencies.min()}ms avg=${latencies.average().toLong()}ms max=${latencies.max()}ms" +
                if (failures > 0) " ($failures failed)" else ""
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

/** 把消息打到 stdout 的最小 agent 子类。 */
private class ConsoleAgent(config: AgentConfig) : Agent(config) {
    override val sink = MessageSink { out ->
        when (out) {
            is OutboundMessage.AgentReply -> {}//println("[agent] ${out.content}")
            is OutboundMessage.ToolReply -> {}//println("[tool:${out.toolName}] ${out.content}")
            is OutboundMessage.Debug -> {}
            is OutboundMessage.Think -> {}
        }
    }
    override val authorizer = ToolAuthorizer { userId, toolName ->
        if (toolName == "shutdown") userId == "admin" else true
    }
}

fun main() = runBlocking {
    // 修复 Windows 控制台中文乱码：强制 stdout 使用 UTF-8
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))

    val apiKey = "sk-Nhxs7MO3HDspptIICNmgobNdmeSc4RcIM6Aa4FLxvqgxeM6S"
    val baseUrl = "http://192.168.0.108:3000/v1" // 可选：兼容 OpenAI 协议的第三方端点

    val agent = ConsoleAgent(
        AgentConfig(
            model = ModelId("deepseek-v4-flash"),
            apiKey = apiKey,
            baseUrl = baseUrl,
            mode = AgentMode.SILENT,
            maxSteps = 6,
            think = true
        )
    )
    agent.registerTool(ShutdownTool())
    agent.registerTool(PingTool())

    // 函数式工厂示例：无需继承 Tool，直接用 tool(...) 创建并注册。
    agent.registerTool(
        tool(
            name = "now",
            description = "Return the current local date-time.",
            parameters = buildJsonObject { put("type", "object") },
        ) { _, _ ->
            val now = java.time.LocalDateTime.now().toString()
            ToolResult(toAgent = now, userReply = "现在时间：$now")
        }
    )

    println("Vicky console agent. Type 'quit' to exit.")
    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line == "quit") break
        if (line.isEmpty()) continue
        agent.receive(InboundMessage(userId = "user1", content = line), {
            when (it) {
                is OutboundMessage.AgentReply -> println("[agent] ${it.content}")
                is OutboundMessage.ToolReply -> println("[tool:${it.toolName}] ${it.content}")
                is OutboundMessage.Debug -> println("[debug] ${it.content}")
                is OutboundMessage.Think -> println("[think] ${it.content}")
            }
        })
    }
}
