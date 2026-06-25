package org.example.vicky.mcp

import org.example.vicky.tool.ToolRegistry
import kotlinx.coroutines.runBlocking

/**
 * MCP 客户端手动测试。
 *
 * 运行前确保有可用的 MCP 服务器，例如：
 *   npx -y @modelcontextprotocol/server-everything
 *
 * 然后运行此测试。
 */
fun main() = runBlocking {
    val manager = McpClientManager()
    val registry = ToolRegistry()

    try {
        // 连接 stdio 模式的 MCP 服务器
        println("Connecting to MCP server...")
        manager.connectStdio(
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-everything"),
            serverName = "everything",
        )

        // 注册所有工具
        manager.registerAllTools(registry)

        // 列出已注册的工具
        println("\n=== Registered MCP Tools ===")
        for (tool in registry.snapshot()) {
            println("  ${tool.name}: ${tool.description.take(80)}")
        }

        // 调用一个工具测试
        val echoTool = registry["echo"]
        if (echoTool != null) {
            println("\n=== Calling 'echo' tool ===")
            val args = kotlinx.serialization.json.buildJsonObject {
                put("message", kotlinx.serialization.json.JsonPrimitive("Hello from Vicky!"))
            }
            val result = echoTool.execute("test-user", args)
            println("Result: ${result.toAgent}")
        } else {
            println("'echo' tool not found, available tools: ${registry.snapshot().map { it.name }}")
        }

    } finally {
        manager.close()
        println("\nDone.")
    }
}
