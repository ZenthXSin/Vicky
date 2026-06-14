package org.example.vicky.tool

import kotlinx.serialization.json.JsonObject

/**
 * 工具抽象。
 *
 * - [parameters] 必须是 OpenAI function-calling 规范的 JSON Schema (object 类型)。
 * - [execute] 第一参数显式传 userId，便于做权限判断。
 */
abstract class Tool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: JsonObject

    abstract suspend fun execute(userId: String, args: JsonObject): ToolResult
}
