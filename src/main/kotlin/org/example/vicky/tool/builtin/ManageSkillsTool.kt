package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.skill.SkillLoader
import org.example.vicky.skill.SkillManager
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult

/**
 * 管理 skill：list / enable / disable / delete。
 *
 * 安装能力 (install action) 暂未实现：URL/ClawHub 源会在下一阶段加入。
 * [onStateChange] 用于在启用/禁用/删除后回调持久化（写回 config.json）。
 */
class ManageSkillsTool(
    private val onStateChange: (() -> Unit)? = null,
) : Tool() {
    override val name = "manage_skills"
    override val description =
        "List all skills, or enable / disable / delete a specific skill by name. " +
            "Use action='list' to see all skills (including disabled ones). " +
            "Use action='enable' or 'disable' with 'name' to toggle. " +
            "Use action='delete' with 'name' to permanently remove a skill directory."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("enable"))
                    add(JsonPrimitive("disable"))
                    add(JsonPrimitive("delete"))
                    add(JsonPrimitive("install"))
                }
                put("description", "Operation: list / enable / disable / delete. (install is reserved, not implemented yet.)")
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Skill name. Required for enable / disable / delete.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "error: missing required parameter 'action'")
        return when (action) {
            "list" -> list()
            "enable" -> toggle(args, enable = true)
            "disable" -> toggle(args, enable = false)
            "delete" -> delete(args)
            "install" -> ToolResult(toAgent = "error: 'install' action is not implemented yet (TODO: URL/ClawHub source)")
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be one of: list, enable, disable, delete.")
        }
    }

    private fun list(): ToolResult {
        val all = SkillManager.allIncludingDisabled()
        if (all.isEmpty()) {
            val root = SkillLoader.rootDir()?.absolutePath ?: "(未初始化)"
            return ToolResult(toAgent = "no skills found. skills dir: $root")
        }
        val sb = StringBuilder()
        val enabled = all.filter { it.enabled }
        val disabled = all.filter { !it.enabled }
        sb.appendLine("=== Enabled skills (${enabled.size}) ===")
        for (s in enabled) sb.appendLine("[enabled]  ${s.name} — ${s.description}")
        if (disabled.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== Disabled skills (${disabled.size}) ===")
            for (s in disabled) sb.appendLine("[disabled] ${s.name} — ${s.description}")
        }
        return ToolResult(toAgent = sb.toString().trimEnd())
    }

    private fun toggle(args: JsonObject, enable: Boolean): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val newState = SkillManager.setEnabled(name, enable)
            ?: return ToolResult(toAgent = "error: skill '$name' not found")
        onStateChange?.invoke()
        return ToolResult(toAgent = "skill '$name' ${if (newState) "enabled" else "disabled"} successfully")
    }

    private fun delete(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        if (SkillManager.find(name) == null) {
            return ToolResult(toAgent = "error: skill '$name' not found")
        }
        val ok = SkillLoader.delete(name)
        if (!ok) return ToolResult(toAgent = "error: failed to delete skill '$name' (filesystem error)")
        onStateChange?.invoke()
        return ToolResult(toAgent = "skill '$name' deleted (directory removed)")
    }
}
