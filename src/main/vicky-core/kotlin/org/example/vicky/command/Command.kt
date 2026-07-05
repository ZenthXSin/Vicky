package org.example.vicky.command

/**
 * 命令抽象基类。
 *
 * - [name] 命令名（不含 `/` 前缀）。
 * - [aliases] 可选别名，注册时一并映射。
 * - [execute] 的 [args] 参数是 `/cmd` 之后的原始字符串，命令自行解析。
 */
abstract class Command {
    abstract val name: String
    open val aliases: List<String> = emptyList()
    abstract val description: String
    open val adminOnly: Boolean = false

    abstract suspend fun execute(ctx: CommandContext, args: String): CommandResult
}

/**
 * 函数式工厂：直接传入字段 + 执行逻辑，返回 [Command]，免去继承样板。
 *
 * ```
 * val ping = command("ping", "Ping the bot") { ctx, args ->
 *     CommandResult(reply = "pong")
 * }
 * ```
 */
fun command(
    name: String,
    description: String,
    aliases: List<String> = emptyList(),
    adminOnly: Boolean = false,
    execute: suspend (ctx: CommandContext, args: String) -> CommandResult,
): Command {
    val n = name
    val d = description
    val a = aliases
    val ao = adminOnly
    val fn = execute
    return object : Command() {
        override val name = n
        override val aliases = a
        override val description = d
        override val adminOnly = ao
        override suspend fun execute(ctx: CommandContext, args: String) = fn(ctx, args)
    }
}
