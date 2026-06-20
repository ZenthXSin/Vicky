package org.example.vicky.tool.builtin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.vicky.annotations.ToolGroup
import org.example.vicky.annotations.ToolParam
import org.example.vicky.annotations.VickyTool
import org.example.vicky.file.FileIndexService
import org.example.vicky.memory.DistillationScheduler
import org.example.vicky.memory.Memory
import org.example.vicky.memory.MemoryStore
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_READ_CHARS = 20_000

private fun safePath(baseDir: File, path: String): File? {
    val canonicalBase = baseDir.canonicalFile
    val target = File(canonicalBase, path).canonicalFile
    if (!target.path.startsWith(canonicalBase.path + File.separator) &&
        target.path != canonicalBase.path
    ) return null
    return target
}

@ToolGroup(name = "builtin")
object BuiltinToolImpl {
    lateinit var baseDir: File
    var memoryStore: MemoryStore? = null
    var fileIndexService: FileIndexService? = null
    var distillationScheduler: DistillationScheduler? = null
    private val indexingRunning = AtomicBoolean(false)
    private val disabledTools = mutableMapOf<String, Tool>()

    fun getDisabledToolNames(): Set<String> = disabledTools.keys.toSet()

    // ─── clear_context ────────────────────────────────────────

    @VickyTool(name = "clear_context", description = "Clear the current conversation history/context. Takes effect from the next message.")
    fun clearContext(ctx: ToolContext): ToolResult {
        ctx.store.clear(ctx.conversationId)
        return ToolResult(
            toAgent = "context cleared for conversation '${ctx.conversationId}'",
            userReply = "上下文已清除。",
        )
    }

    // ─── github ───────────────────────────────────────────────

    @VickyTool(name = "github", description = "Browse a GitHub repository. Pass 'repo' and an optional 'path': if 'path' is a directory it returns that directory's immediate entries; if 'path' is a file it returns the file content.")
    suspend fun github(
        @ToolParam(description = "Repository as 'owner/repo', e.g. 'Anuken/Mindustry'.") repo: String,
        @ToolParam(description = "Directory or file path inside the repo. Empty = repo root.", required = false) path: String = "",
        @ToolParam(description = "Branch/tag/commit. Omit to use the repo default branch.", required = false) ref: String = "",
    ): ToolResult {
        val tool = GithubTool()
        return tool.execute("system", buildJsonObject {
            put("repo", kotlinx.serialization.json.JsonPrimitive(repo))
            if (path.isNotEmpty()) put("path", kotlinx.serialization.json.JsonPrimitive(path))
            if (ref.isNotEmpty()) put("ref", kotlinx.serialization.json.JsonPrimitive(ref))
        })
    }

    // ─── file_read ────────────────────────────────────────────

    @VickyTool(name = "file_read", description = "Read a text file by relative path. Truncated at 20K chars.")
    fun fileRead(
        @ToolParam(description = "Relative file path, e.g. 'src/main.kt' or 'README.md'.") path: String,
    ): ToolResult {
        val trimmed = path.trim()
        val file = safePath(baseDir, trimmed)
            ?: return ToolResult(toAgent = "Error: path '$trimmed' is outside the allowed directory.")
        if (!file.exists()) return ToolResult(toAgent = "Error: file '$trimmed' not found.")
        if (file.isDirectory) return ToolResult(toAgent = "Error: '$trimmed' is a directory, not a file. Use file_list to list directory contents.")
        if (file.length() == 0L) return ToolResult(toAgent = "File '$trimmed' is empty (0 B).")

        val bytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult(toAgent = "Error reading '$trimmed': ${it.message}")
        }
        val text = String(bytes, Charsets.UTF_8)
        val replacementCount = text.count { it == '\uFFFD' }
        if (replacementCount > bytes.size / 100) {
            return ToolResult(toAgent = "File '$trimmed' (${file.length()} B) appears to be binary. Cannot display content.")
        }
        val capped = if (text.length > MAX_READ_CHARS) {
            text.take(MAX_READ_CHARS) + "\n... [truncated, ${text.length - MAX_READ_CHARS} more chars]"
        } else {
            text
        }
        return ToolResult(toAgent = "File '$trimmed' (${file.length()} B):\n$capped")
    }

    // ─── file_write ───────────────────────────────────────────

    @VickyTool(name = "file_write", description = "Write text to a file. Creates parent dirs. Append mode available.")
    fun fileWrite(
        @ToolParam(description = "Relative file path, e.g. 'output/result.txt'.") path: String,
        @ToolParam(description = "The text content to write.") content: String,
        @ToolParam(description = "If true, append to file instead of overwriting. Default false.", required = false) append: Boolean = false,
    ): ToolResult {
        val trimmed = path.trim()
        val file = safePath(baseDir, trimmed)
            ?: return ToolResult(toAgent = "Error: path '$trimmed' is outside the allowed directory.")
        if (file.exists() && !file.canWrite()) {
            return ToolResult(toAgent = "Error: no write permission for '$trimmed'.")
        }
        if (file.parentFile != null && !file.parentFile!!.canWrite()) {
            return ToolResult(toAgent = "Error: no write permission for directory '${file.parentFile}'.")
        }
        runCatching {
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content, Charsets.UTF_8)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
        }.getOrElse {
            return ToolResult(toAgent = "Error writing to '$trimmed': ${it.message}")
        }
        val bytesWritten = content.toByteArray(Charsets.UTF_8).size
        val mode = if (append) "appended to" else "written to"
        return ToolResult(toAgent = "Successfully ${mode} '$trimmed' ($bytesWritten bytes).")
    }

    // ─── file_list ────────────────────────────────────────────

    @VickyTool(name = "file_list", description = "List directory contents with file sizes.")
    fun fileList(
        @ToolParam(description = "Relative directory path. Empty or '.' = project root.", required = false) path: String = ".",
        @ToolParam(description = "Optional glob filter, e.g. '*.kt' or '**/*.json'.", required = false) pattern: String = "",
    ): ToolResult {
        val trimmed = path.trim()
        val dir = safePath(baseDir, trimmed)
            ?: return ToolResult(toAgent = "Error: path '$trimmed' is outside the allowed directory.")
        if (!dir.exists()) return ToolResult(toAgent = "Error: directory '$trimmed' not found.")
        if (!dir.isDirectory) return ToolResult(toAgent = "Error: '$trimmed' is a file, not a directory.")

        val entries = if (pattern.isNotEmpty()) {
            dir.listFiles()?.filter { it.name.matches(Regex(pattern.replace("*", ".*"))) } ?: emptyList()
        } else {
            dir.listFiles()?.toList() ?: emptyList()
        }
        if (entries.isEmpty()) return ToolResult(toAgent = "Directory '$trimmed' is empty.")

        val sorted = entries.sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }))
        val lines = sorted.map { entry ->
            if (entry.isDirectory) "[dir]  ${entry.name}" else "[file] ${entry.name} (${entry.length()} B)"
        }
        val basePath = if (trimmed == ".") baseDir.name else trimmed
        return ToolResult(toAgent = "Directory '$basePath' (${entries.size} items):\n${lines.joinToString("\n")}")
    }

    // ─── memory_store ─────────────────────────────────────────

    @VickyTool(name = "memory_store", description = "Store a piece of information into long-term memory. Use this to remember important facts, preferences, or decisions.")
    suspend fun memoryStore(
        ctx: ToolContext,
        @ToolParam(description = "The content to remember.") content: String,
        @ToolParam(description = "Optional comma-separated tags for categorization.", required = false) tags: String = "",
    ): ToolResult {
        val store = memoryStore ?: return ToolResult(toAgent = "Error: memory system is not enabled.")
        if (content.isBlank()) return ToolResult(toAgent = "Error: content cannot be empty.")

        val tagSet = if (tags.isBlank()) emptySet() else tags.split(",").map { it.trim() }.toSet()
        val memory = Memory(
            content = content,
            summary = content,
            tags = tagSet,
            userId = ctx.userId,
            source = "user_stated",
            confidence = 1.0f,
        )
        store.remember(memory)
        return ToolResult(toAgent = "Memory stored successfully.", userReply = "已记住。")
    }

    // ─── memory_search ────────────────────────────────────────

    @VickyTool(name = "memory_search", description = "Search long-term memory using semantic similarity. Use this to recall previously stored information.")
    suspend fun memorySearch(
        ctx: ToolContext,
        @ToolParam(description = "The search query.") query: String,
        @ToolParam(description = "Number of results to return. Default 5.", required = false) top_k: Int = 5,
    ): ToolResult {
        val store = memoryStore ?: return ToolResult(toAgent = "Error: memory system is not enabled.")
        val memories = store.recall(query, ctx.userId, top_k)
        if (memories.isEmpty()) return ToolResult(toAgent = "No relevant memories found.")
        val result = memories.joinToString("\n") { "- [${it.source}] ${it.content}" }
        return ToolResult(toAgent = "Found ${memories.size} relevant memories:\n$result")
    }

    // ─── memory_distill ───────────────────────────────────────

    @VickyTool(name = "memory_distill", description = "Manually trigger memory distillation. This processes raw conversation memories and extracts key information.")
    suspend fun memoryDistill(): ToolResult {
        val scheduler = distillationScheduler
            ?: return ToolResult(toAgent = "Error: memory distillation is not enabled.")
        return try {
            scheduler.distillNow()
            ToolResult(toAgent = "Memory distillation completed successfully.", userReply = "记忆蒸馏完成。")
        } catch (e: Exception) {
            ToolResult(toAgent = "Error during distillation: ${e.message}", userReply = "蒸馏失败：${e.message}")
        }
    }

    // ─── file_search ──────────────────────────────────────────

    @VickyTool(name = "file_search", description = "Semantic search indexed files by content.")
    suspend fun fileSearch(
        @ToolParam(description = "The search query.") query: String,
        @ToolParam(description = "Number of results to return. Default 5.", required = false) top_k: Int = 5,
    ): ToolResult {
        val service = fileIndexService
            ?: return ToolResult(toAgent = "Error: file indexing is not enabled.")
        val results = service.search(query, top_k)
        if (results.isEmpty()) return ToolResult(toAgent = "No matching files found.")
        val result = results.joinToString("\n\n") { sr ->
            "File: ${sr.path} (lines ${sr.startLine}-${sr.endLine}, score: %.2f)\n${sr.chunk}".format(sr.score)
        }
        return ToolResult(toAgent = "Found ${results.size} matching results:\n$result")
    }

    // ─── file_index ───────────────────────────────────────────

    @VickyTool(name = "file_index", description = "Trigger background incremental indexing of files under the working directory. Returns immediately; progress is logged.")
    fun fileIndex(): ToolResult {
        val service = fileIndexService
            ?: return ToolResult(toAgent = "Error: file indexing is not enabled.")
        if (!indexingRunning.compareAndSet(false, true)) {
            return ToolResult(toAgent = "indexing already in progress", userReply = "已有索引任务进行中。")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = service.indexAll { current, success, skipped ->
                    print("\r[Vicky] 索引中: 已处理 $current 个文件，$success 个已索引，$skipped 个已跳过")
                }
                println("\n[Vicky] 文件索引完成: ${r.newFiles} 个新增，${r.updatedFiles} 个更新，${r.skippedFiles} 个跳过")
            } catch (e: Exception) {
                println("\n[Vicky] 文件索引失败: ${e.message}")
            } finally {
                indexingRunning.set(false)
            }
        }
        return ToolResult(toAgent = "indexing started in background", userReply = "已在后台开始索引文件。")
    }

    // ─── manage_tools ─────────────────────────────────────────

    @VickyTool(name = "manage_tools", description = "List all tools and their status, or enable/disable a specific tool at runtime. Use action='list' to see all tools. Use action='disable' or 'enable' with tool_name to toggle.")
    fun manageTools(
        ctx: ToolContext,
        @ToolParam(description = "Operation: list all tools, enable a disabled tool, or disable an active tool.") action: String,
        @ToolParam(description = "Name of the tool to enable or disable. Required for enable/disable actions.", required = false) tool_name: String = "",
    ): ToolResult {
        return when (action) {
            "list" -> {
                val sb = StringBuilder()
                val activeTools = ctx.tools.snapshot().sortedBy { it.name }
                val disabledList = disabledTools.values.sortedBy { it.name }
                sb.appendLine("=== Active tools (${activeTools.size}) ===")
                for (tool in activeTools) sb.appendLine("[active]   ${tool.name} — ${tool.description}")
                if (disabledList.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("=== Disabled tools (${disabledList.size}) ===")
                    for (tool in disabledList) sb.appendLine("[disabled] ${tool.name} — ${tool.description}")
                }
                ToolResult(toAgent = sb.toString().trimEnd())
            }
            "enable" -> {
                val name = tool_name.trim()
                if (name.isEmpty()) return ToolResult(toAgent = "error: missing required parameter 'tool_name'")
                val tool = disabledTools.remove(name)
                    ?: return ToolResult(toAgent = if (ctx.tools[name] != null) "error: tool '$name' is already active" else "error: tool '$name' not found in disabled tools")
                ctx.tools.register(tool)
                ToolResult(toAgent = "tool '$name' enabled successfully")
            }
            "disable" -> {
                val name = tool_name.trim()
                if (name.isEmpty()) return ToolResult(toAgent = "error: missing required parameter 'tool_name'")
                if (name == "manage_tools") return ToolResult(toAgent = "error: cannot disable 'manage_tools' (the tool management tool itself)")
                val tool = ctx.tools.unregister(name)
                    ?: return ToolResult(toAgent = if (disabledTools.containsKey(name)) "error: tool '$name' is already disabled" else "error: tool '$name' not found")
                disabledTools[name] = tool
                ToolResult(toAgent = "tool '$name' disabled successfully")
            }
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be list, enable, or disable.")
        }
    }
}
