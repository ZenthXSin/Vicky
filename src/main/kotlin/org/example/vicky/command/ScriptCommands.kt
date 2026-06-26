package org.example.vicky.command

import org.example.vicky.script.ScriptManager
import org.example.vicky.tool.ToolRegistry
import java.io.File

/**
 * 脚本管理命令：/script load | reload | enable | disable | list
 */
object ScriptCommands {

    fun all(scriptsDir: File, toolRegistry: ToolRegistry): List<Command> = listOf(
        command(
            name = "script",
            description = "脚本管理: /script load|reload|enable|disable|list",
            aliases = listOf("s"),
        ) { _, args ->
            val parts = args.split(Regex("\\s+"), limit = 2)
            val action = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "" }.trim()

            when (action) {
                "load" -> {
                    if (target.isEmpty()) return@command CommandResult(reply = "用法: /script load <文件名>")
                    val file = resolveScriptFile(scriptsDir, target)
                        ?: return@command CommandResult(reply = "未找到脚本: $target")
                    try {
                        val bridge = ScriptManager.loadAndRegister(file, toolRegistry)
                        CommandResult(reply = "已加载: ${bridge.name}")
                    } catch (e: Exception) {
                        CommandResult(reply = "加载失败: ${e.message}")
                    }
                }

                "reload" -> {
                    if (target.isEmpty()) return@command CommandResult(reply = "用法: /script reload <文件名>")
                    val file = resolveScriptFile(scriptsDir, target)
                        ?: return@command CommandResult(reply = "未找到脚本: $target")
                    try {
                        ScriptManager.reloadScript(file, toolRegistry)
                        CommandResult(reply = "已重载: ${file.nameWithoutExtension}")
                    } catch (e: Exception) {
                        CommandResult(reply = "重载失败: ${e.message}")
                    }
                }

                "enable" -> {
                    if (target.isEmpty()) return@command CommandResult(reply = "用法: /script enable <文件名>")
                    val file = resolveScriptFile(scriptsDir, target)
                        ?: return@command CommandResult(reply = "未找到脚本: $target")
                    try {
                        ScriptManager.loadAndRegister(file, toolRegistry)
                        CommandResult(reply = "已启用: ${file.nameWithoutExtension}")
                    } catch (e: Exception) {
                        CommandResult(reply = "启用失败: ${e.message}")
                    }
                }

                "disable" -> {
                    if (target.isEmpty()) return@command CommandResult(reply = "用法: /script disable <工具名/文件名>")
                    val query = target.removeSuffix(".ts")
                    val entry = ScriptManager.loadedScripts().entries.find { (key, bridge) ->
                        key.removeSuffix(".ts") == query || bridge.name == query
                    }
                    if (entry == null) return@command CommandResult(reply = "未找到已加载的脚本: $query")
                    ScriptManager.unloadScript(entry.key, toolRegistry)
                    CommandResult(reply = "已禁用: ${entry.value.name}")
                }

                "list" -> {
                    val scripts = ScriptManager.loadedScripts()
                    if (scripts.isEmpty()) return@command CommandResult(reply = "当前无已加载的脚本")
                    val sb = StringBuilder("已加载的脚本 (${scripts.size}):\n")
                    for ((name, bridge) in scripts) {
                        sb.appendLine("  $name → ${bridge.name}")
                    }
                    CommandResult(reply = sb.toString().trimEnd())
                }

                else -> CommandResult(reply = "用法: /script load|reload|enable|disable|list [参数]")
            }
        },
    )

    private fun resolveScriptFile(scriptsDir: File, name: String): File? {
        val withExt = if (name.endsWith(".ts")) name else "$name.ts"
        val file = File(scriptsDir, withExt)
        return if (file.exists()) file else null
    }
}
