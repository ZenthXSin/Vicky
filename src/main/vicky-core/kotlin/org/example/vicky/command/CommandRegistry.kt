package org.example.vicky.command

import java.util.concurrent.ConcurrentHashMap

/**
 * 命令注册表，支持运行时增/删/查/分发。
 */
class CommandRegistry {
    /** name/alias → Command 的映射。 */
    private val commands = ConcurrentHashMap<String, Command>()
    /** name → Command 的正向映射（用于 snapshot 去重）。 */
    private val byName = ConcurrentHashMap<String, Command>()

    fun register(command: Command) {
        byName[command.name] = command
        commands[command.name] = command
        for (alias in command.aliases) {
            commands[alias] = command
        }
    }

    fun unregister(name: String): Command? {
        val cmd = byName.remove(name) ?: return null
        commands.remove(name)
        for (alias in cmd.aliases) {
            commands.remove(alias)
        }
        return cmd
    }

    /** 按 name 或 alias 查找命令。 */
    operator fun get(name: String): Command? = commands[name]

    /** 所有已注册命令（去重，用于 /help 等）。 */
    fun snapshot(): List<Command> = byName.values.toList()

    /**
     * 分发入口。
     *
     * - [rawInput] 不以 `/` 开头 → 返回 `null`（非命令，交给 Agent）。
     * - 解析 `/cmd args...` → 查找命令 → 执行 → 返回结果。
     * - 命令未找到 → 返回 `CommandResult(reply = "未知命令")`。
     */
    suspend fun dispatch(ctx: CommandContext, rawInput: String): CommandResult? {
        if (!rawInput.startsWith("/")) return null

        val trimmed = rawInput.removePrefix("/").trim()
        if (trimmed.isEmpty()) return CommandResult(reply = "请输入命令，例如 /help")

        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmdName = parts[0]
        val args = parts.getOrElse(1) { "" }

        val cmd = commands[cmdName]
            ?: return CommandResult(reply = "未知命令: /$cmdName")

        return cmd.execute(ctx, args)
    }
}
