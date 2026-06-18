package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult
import java.io.File

private const val MAX_READ_CHARS = 20_000

// ─── 路径安全校验 ────────────────────────────────────────────

/**
 * 将用户输入的相对路径解析为安全的绝对路径。
 * 若解析后不在 baseDir 内则返回 null（拒绝 ../ 等逃逸）。
 */
private fun safePath(baseDir: File, path: String): File? {
    val canonicalBase = baseDir.canonicalFile
    val target = File(canonicalBase, path).canonicalFile
    if (!target.path.startsWith(canonicalBase.path + File.separator) &&
        target.path != canonicalBase.path
    ) return null
    return target
}

// ─── file_read ───────────────────────────────────────────────

class FileReadTool(private val baseDir: File) : Tool() {
    override val name = "file_read"
    override val description =
        "Read a text file's content. Pass a relative path from the project root. " +
            "Returns file content (truncated at 20000 chars). " +
            "Binary files and paths outside the project directory are rejected."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative file path, e.g. 'src/main.kt' or 'README.md'.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'path'.")
        val file = safePath(baseDir, path)
            ?: return ToolResult(toAgent = "Error: path '$path' is outside the allowed directory.")

        if (!file.exists()) return ToolResult(toAgent = "Error: file '$path' not found.")
        if (file.isDirectory) return ToolResult(toAgent = "Error: '$path' is a directory, not a file. Use file_list to list directory contents.")
        if (file.length() == 0L) return ToolResult(toAgent = "File '$path' is empty (0 B).")

        val bytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult(toAgent = "Error reading '$path': ${it.message}")
        }

        val text = String(bytes, Charsets.UTF_8)
        val replacementCount = text.count { it == '\uFFFD' }
        if (replacementCount > bytes.size / 100) {
            return ToolResult(
                toAgent = "File '$path' (${file.length()} B) appears to be binary. Cannot display content."
            )
        }

        val capped = if (text.length > MAX_READ_CHARS) {
            text.take(MAX_READ_CHARS) +
                "\n... [truncated, ${text.length - MAX_READ_CHARS} more chars]"
        } else {
            text
        }
        return ToolResult(toAgent = "File '$path' (${file.length()} B):\n$capped")
    }
}

// ─── file_write ──────────────────────────────────────────────

class FileWriteTool(private val baseDir: File) : Tool() {
    override val name = "file_write"
    override val description =
        "Write content to a text file. Creates parent directories if needed. " +
            "Overwrites the file by default; pass append=true to append. " +
            "Paths outside the project directory are rejected."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative file path, e.g. 'output/result.txt'.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "The text content to write.")
            }
            putJsonObject("append") {
                put("type", "boolean")
                put("description", "If true, append to file instead of overwriting. Default false.")
            }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("content"))
        })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "Error: missing 'path'.")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "Error: missing 'content'.")
        val append = args["append"]?.jsonPrimitive?.boolean ?: false

        val file = safePath(baseDir, path)
            ?: return ToolResult(toAgent = "Error: path '$path' is outside the allowed directory.")

        if (file.exists() && !file.canWrite()) {
            return ToolResult(toAgent = "Error: no write permission for '$path'.")
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
            return ToolResult(toAgent = "Error writing to '$path': ${it.message}")
        }

        val bytesWritten = content.toByteArray(Charsets.UTF_8).size
        val mode = if (append) "appended to" else "written to"
        return ToolResult(toAgent = "Successfully ${mode} '$path' ($bytesWritten bytes).")
    }
}

// ─── file_list ───────────────────────────────────────────────

class FileListTool(private val baseDir: File) : Tool() {
    override val name = "file_list"
    override val description =
        "List contents of a directory. Shows subdirectories and files with sizes. " +
            "Optionally filter by glob pattern. Paths outside the project directory are rejected."
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Relative directory path. Empty or '.' = project root.")
            }
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "Optional glob filter, e.g. '*.kt' or '**/*.json'.")
            }
        }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content?.trim() ?: "."
        val pattern = args["pattern"]?.jsonPrimitive?.content?.trim()

        val dir = safePath(baseDir, path)
            ?: return ToolResult(toAgent = "Error: path '$path' is outside the allowed directory.")
        if (!dir.exists()) return ToolResult(toAgent = "Error: directory '$path' not found.")
        if (!dir.isDirectory) return ToolResult(toAgent = "Error: '$path' is a file, not a directory.")

        val entries = if (pattern != null) {
            dir.listFiles()?.filter { it.name.matches(Regex(pattern.replace("*", ".*"))) } ?: emptyList()
        } else {
            dir.listFiles()?.toList() ?: emptyList()
        }

        if (entries.isEmpty()) {
            return ToolResult(toAgent = "Directory '$path' is empty.")
        }

        val sorted = entries.sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }))
        val lines = sorted.map { entry ->
            val name = entry.name
            if (entry.isDirectory) "[dir]  $name"
            else "[file] $name (${entry.length()} B)"
        }

        val basePath = if (path == ".") baseDir.name else path
        return ToolResult(
            toAgent = "Directory '$basePath' (${entries.size} items):\n${lines.joinToString("\n")}"
        )
    }
}
