package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.vicky.memory.DistillationScheduler
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult

/**
 * 手动触发记忆蒸馏。
 */
class MemoryDistillTool(
    private val scheduler: DistillationScheduler,
) : Tool() {
    override val name = "memory_distill"
    override val description =
        "Manually trigger memory distillation. " +
            "This processes raw conversation memories and extracts key information."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        return try {
            scheduler.distillNow()
            ToolResult(
                toAgent = "Memory distillation completed successfully.",
                userReply = "记忆蒸馏完成。",
            )
        } catch (e: Exception) {
            ToolResult(
                toAgent = "Error during distillation: ${e.message}",
                userReply = "蒸馏失败：${e.message}",
            )
        }
    }
}
