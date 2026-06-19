package org.example.vicky.tool.builtin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.vicky.file.FileIndexService
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult
import java.util.concurrent.atomic.AtomicBoolean

/** 手动触发后台增量索引：立刻返回，索引在后台跑，日志输出进度。 */
class FileIndexTool(
    private val fileIndexService: FileIndexService?,
) : Tool() {
    override val name = "file_index"
    override val description =
        "Trigger background incremental indexing of files under the working directory. Returns immediately; progress is logged."

    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    private val running = AtomicBoolean(false)

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        if (fileIndexService == null) {
            return ToolResult(toAgent = "Error: file indexing is not enabled.")
        }
        if (!running.compareAndSet(false, true)) {
            return ToolResult(toAgent = "indexing already in progress", userReply = "已有索引任务进行中。")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = fileIndexService.indexAll { current, success, skipped ->
                    print("\r[Vicky] 索引中: 已处理 $current 个文件，$success 个已索引，$skipped 个已跳过")
                }
                println("\n[Vicky] 文件索引完成: ${r.newFiles} 个新增，${r.updatedFiles} 个更新，${r.skippedFiles} 个跳过")
            } catch (e: Exception) {
                println("\n[Vicky] 文件索引失败: ${e.message}")
            } finally {
                running.set(false)
            }
        }
        return ToolResult(toAgent = "indexing started in background", userReply = "已在后台开始索引文件。")
    }
}

