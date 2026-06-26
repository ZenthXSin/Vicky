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
            putJsonObject("group") {
                put("type", "string")
                put("description", "Filter by group name. Optional for list action.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "error: missing required parameter 'action'")
        return when (action) {
            "list" -> list(args)
            "enable" -> toggle(args, enable = true)
            "disable" -> toggle(args, enable = false)
            "delete" -> delete(args)
            "install" -> ToolResult(toAgent = "error: 'install' action is not implemented yet (TODO: URL/ClawHub source)")
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be one of: list, enable, disable, delete.")
        }
    }

    private fun list(args: JsonObject): ToolResult {
        val filterGroup = args["group"]?.jsonPrimitive?.content?.trim()
        val all = SkillManager.allIncludingDisabled()
        if (all.isEmpty()) {
            val root = SkillLoader.rootDir()?.absolutePath ?: "(未初始化)"
            return ToolResult(toAgent = "no skills found. skills dir: $root")
        }

        val sb = StringBuilder()

        // 如果按分组过滤
        if (filterGroup != null) {
            val groupSkills = all.filter { it.group == filterGroup }
            if (groupSkills.isEmpty()) {
                return ToolResult(toAgent = "no skills found in group '$filterGroup'")
            }
            val groupDesc = SkillManager.groupDescription(filterGroup) ?: ""
            sb.appendLine("=== Group: $filterGroup${if (groupDesc.isNotEmpty()) " — $groupDesc" else ""} (${groupSkills.size}) ===")
            for (s in groupSkills) {
                val status = if (s.enabled) "enabled" else "disabled"
                sb.appendLine("[$status] ${s.name} — ${s.description}")
            }
            return ToolResult(toAgent = sb.toString().trimEnd())
        }

        // 无分组的技能
        val ungrouped = all.filter { it.group.isEmpty() }
        val enabledUngrouped = ungrouped.filter { it.enabled }
        val disabledUngrouped = ungrouped.filter { !it.enabled }

        if (enabledUngrouped.isNotEmpty()) {
            sb.appendLine("=== Enabled skills (${enabledUngrouped.size}) ===")
            for (s in enabledUngrouped) sb.appendLine("[enabled]  ${s.name} — ${s.description}")
        }
        if (disabledUngrouped.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== Disabled skills (${disabledUngrouped.size}) ===")
            for (s in disabledUngrouped) sb.appendLine("[disabled] ${s.name} — ${s.description}")
        }

        // 分组技能概览
        val groups = SkillManager.groups()
        if (groups.isNotEmpty()) {
            sb.appendLine()
            for ((groupName, groupDesc) in groups) {
                val groupSkills = all.filter { it.group == groupName }
                val enabledCount = groupSkills.count { it.enabled }
                sb.appendLine("=== Group: $groupName${if (groupDesc.isNotEmpty()) " — $groupDesc" else ""} ($enabledCount/${groupSkills.size} enabled) ===")
                for (s in groupSkills) {
                    val status = if (s.enabled) "enabled" else "disabled"
                    sb.appendLine("  [$status] ${s.name} — ${s.description}")
                }
            }
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
