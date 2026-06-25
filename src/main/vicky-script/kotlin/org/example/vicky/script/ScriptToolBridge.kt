package org.example.vicky.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult

/**
 * 将 JS 导出的 Tool 定义适配为 vicky [Tool] 抽象类。
 */
class ScriptToolBridge(
    private val engine: ScriptEngine,
    private val exports: ScriptExports,
) : Tool() {

    override val name: String = exports.name
    override val description: String = exports.description
    override val parameters: JsonObject = parseParameters(exports.parameters)

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        throw UnsupportedOperationException("Use execute(ctx, args)")
    }

    override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolResult {
        return try {
            val result = engine.callExecute(exports, ctx, args)
            ToolResult(
                toAgent = result["toAgent"]?.toString() ?: "Script returned no content",
                userReply = result["userReply"]?.toString(),
                endTurn = result["endTurn"]?.toString()?.toBooleanStrictOrNull() ?: false,
            )
        } catch (e: ScriptException) {
            ToolResult(toAgent = "Script error in '$name': ${e.message}")
        } catch (e: Exception) {
            ToolResult(toAgent = "Error executing script tool '$name': ${e.message}")
        }
    }

    fun release() {
        engine.releaseExports(exports)
    }

    companion object {
        private val json = Json { isLenient = true }

        fun parseParameters(parametersJson: String): JsonObject {
            return try {
                json.parseToJsonElement(parametersJson) as? JsonObject
                    ?: buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    }
            } catch (_: Exception) {
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                }
            }
        }
    }
}
