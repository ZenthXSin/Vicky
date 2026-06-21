package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.skill.SkillManager
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult

/**
 * 加载一个 skill 的完整指南（body）作为 toAgent 返回，
 * 之后 LLM 按指南操作。被禁用或不存在均返回 Error。
 */
class InvokeSkillTool : Tool() {
    override val name = "invoke_skill"
    override val description =
        "Load a skill's full instructions by name. Returns the skill body verbatim for the model to follow."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "The skill name (matches the 'name' field in SKILL.md frontmatter).")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val n = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val skill = SkillManager.find(n)
            ?: return ToolResult(toAgent = "error: skill '$n' not found")
        if (!skill.enabled) {
            return ToolResult(toAgent = "error: skill '$n' is currently disabled")
        }
        val header = "# Skill: ${skill.name}\n> ${skill.description}\n\n"
        return ToolResult(toAgent = header + skill.body)
    }
}
