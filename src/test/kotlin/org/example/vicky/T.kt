package org.example.vicky

import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.tool.ToolResult

object ExampleTools {

    @VickyTool(name = "ping", description = "测试网络连通性")
    fun ping(
        @ToolParam(description = "目标 IP 地址") host: String,
        @ToolParam(description = "端口号", required = false) port: Int = 80,
    ): String = "Pinging $host:$port"

    @VickyTool(name = "whoami", description = "返回调用方的用户 ID")
    fun whoami(userId: String): ToolResult = ToolResult(toAgent = "userId=$userId", userReply = "你是 $userId")

    @VickyTool(name = "greet", description = "向用户问好，可指定语言")
    fun greet(
        userId: String,
        @ToolParam(description = "问候语言，默认中文", required = false) lang: String = "zh",
    ): ToolResult {
        val msg = if (lang == "en") "Hello, $userId" else "你好，$userId"
        return ToolResult(toAgent = "greeted $userId in $lang", userReply = msg)
    }

}
