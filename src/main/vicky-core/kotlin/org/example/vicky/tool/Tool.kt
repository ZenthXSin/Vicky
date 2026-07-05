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

    open fun isConcurrencySafe(args: JsonObject): Boolean = false

    /**
     * 带上下文的执行入口。框架实际调用此方法，默认委托给 [execute] (userId, args)。
     * 需要访问会话/注册表的工具 (如内置的清除上下文) 可 override 此方法。
     */
    open suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult =
        execute(ctx.userId, args)
}

/**
 * 函数式工厂：直接传入字段 + 执行逻辑，返回 [Tool]，免去继承样板。
 *
 * ```
 * val echo = tool("echo", "Echo text.", schema) { userId, args ->
 *     ToolResult(toAgent = "ok", userReply = args["text"]?.jsonPrimitive?.content)
 * }
 * ```
 */
fun tool(
    name: String,
    description: String,
    parameters: JsonObject,
    execute: suspend (userId: String, args: JsonObject) -> ToolResult,
): Tool {
    val n = name
    val d = description
    val p = parameters
    val fn = execute
    return object : Tool() {
        override val name = n
        override val description = d
        override val parameters = p
        override suspend fun execute(userId: String, args: JsonObject) = fn(userId, args)
    }
}
