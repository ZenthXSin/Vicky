package org.example.vicky.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.example.vicky.platform.RuntimePlatform
import org.example.vicky.tool.ToolRegistry

/**
 * MCP 客户端管理器。
 *
 * 负责连接 MCP 服务器、发现工具并注册到 Vicky 的 [ToolRegistry]。
 * 支持 stdio（本地子进程）和 HTTP（远程服务器）两种传输方式。
 */
class McpClientManager : AutoCloseable {

    private val connections = mutableListOf<Pair<Client, String>>() // client to serverName
    private var httpClient: HttpClient? = null

    /**
     * 连接 stdio 模式的 MCP 服务器（启动子进程）。
     *
     * @param command 要执行的命令，如 "npx"
     * @param args 命令参数，如 ["-y", "@modelcontextprotocol/server-filesystem", "/path"]
     * @param serverName 服务器名称，用于标识工具来源
     */
    suspend fun connectStdio(command: String, args: List<String> = emptyList(), serverName: String = "") {
        check(!RuntimePlatform.isAndroid) {
            "MCP stdio transport is not supported on Android; use connectHttp() instead."
        }
        val pb = ProcessBuilder(listOf(command) + args)
        val process = pb.start()

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )

        val client = Client(
            clientInfo = Implementation(name = "vicky", version = "1.0.0"),
        )
        client.connect(transport)
        connections.add(client to serverName)
    }

    /**
     * 连接 HTTP 模式的 MCP 服务器。
     *
     * @param url 服务器地址，如 "http://localhost:3000/mcp"
     * @param serverName 服务器名称
     */
    suspend fun connectHttp(url: String, serverName: String = "") {
        val hc = httpClient ?: HttpClient(OkHttp) { install(SSE) }.also { httpClient = it }

        val transport = StreamableHttpClientTransport(
            client = hc,
            url = url,
        )

        val client = Client(
            clientInfo = Implementation(name = "vicky", version = "1.0.0"),
        )
        client.connect(transport)
        connections.add(client to serverName)
    }

    /**
     * 从所有已连接的 MCP 服务器发现工具并注册到 [ToolRegistry]。
     */
    suspend fun registerAllTools(registry: ToolRegistry) {
        for ((client, serverName) in connections) {
            val toolsResult = client.listTools()
            for (mcpTool in toolsResult.tools) {
                val vickyTool = McpTool(client, mcpTool, serverName)
                registry.register(vickyTool)
            }
        }
    }

    override fun close() {
        // 在独立线程中执行 suspend close，避免在协程上下文中 runBlocking 死锁
        val conns = connections.toList()
        connections.clear()
        if (conns.isNotEmpty()) {
            val thread = Thread({
                conns.forEach { (client, _) ->
                    runCatching { kotlinx.coroutines.runBlocking { client.close() } }
                }
            }, "mcp-close")
            thread.isDaemon = true
            thread.start()
            thread.join(5000) // 最多等 5 秒
        }
        httpClient?.close()
        httpClient = null
    }
}
