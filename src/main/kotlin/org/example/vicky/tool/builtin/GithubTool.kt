package org.example.vicky.tool.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolResult
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * 访问 GitHub 仓库：列目录 / 读文件。基于 GitHub Contents API，单端点按 path 自动判断。
 *
 * - path 为目录 (或留空=根目录) -> 返回该目录的直接子项列表，agent 可逐级深入。
 * - path 为文件 -> 返回文件内容 (Base64 解码)。
 *
 * token 非空时带 Authorization 头 (5000 次/小时)，否则匿名 (60 次/小时)。
 */
class GithubTool(
    private val token: String? = System.getenv("GITHUB_TOKEN"),
) : Tool() {
    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override val name = "github"
    override val description =
        "Browse a GitHub repository. Built-in suggestions: 'ZenthXSin/Vicky', 'Anuken/Mindustry' " +
            "(but any 'owner/repo' works). Pass 'repo' and an optional 'path': if 'path' is a directory " +
            "(or empty = repo root) it returns that directory's immediate entries so you can drill down " +
            "level by level; if 'path' is a file it returns the file content. Optional 'ref' = branch/tag/commit."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("repo") {
                put("type", "string")
                put("description", "Repository as 'owner/repo', e.g. 'Anuken/Mindustry'.")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Directory or file path inside the repo. Empty = repo root.")
            }
            putJsonObject("ref") {
                put("type", "string")
                put("description", "Branch/tag/commit. Omit to use the repo default branch.")
            }
        }
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("repo")) })
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val repo = args["repo"]?.jsonPrimitive?.content?.trim()?.trim('/')
            ?: return ToolResult(toAgent = "Error: missing 'repo'.")
        val parts = repo.split("/")
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            return ToolResult(toAgent = "Error: 'repo' must be 'owner/repo', got '$repo'.")
        }
        val (owner, name) = parts
        val path = args["path"]?.jsonPrimitive?.content?.trim()?.trim('/').orEmpty()
        val ref = args["ref"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        return runCatching { fetch(owner, name, path, ref) }
            .getOrElse { ToolResult(toAgent = "Error accessing GitHub: ${it.message}") }
    }

    private suspend fun fetch(owner: String, name: String, path: String, ref: String?): ToolResult {
        val encodedPath = path.split("/").filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        val base = "https://api.github.com/repos/$owner/$name/contents/$encodedPath"
        val url = if (ref != null) "$base?ref=${URLEncoder.encode(ref, Charsets.UTF_8)}" else base

        val reqBuilder = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Vicky")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
        if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")

        val resp = withContext(Dispatchers.IO) {
            http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        }

        if (resp.statusCode() !in 200..299) {
            return ToolResult(toAgent = errorFor(resp, "$owner/$name", path))
        }

        val element = json.parseToJsonElement(resp.body())
        return when {
            element is JsonArray -> ToolResult(toAgent = formatListing(owner, name, path, element))
            element is JsonObject -> formatFile(element)
            else -> ToolResult(toAgent = "Error: unexpected GitHub response.")
        }
    }

    private fun formatListing(owner: String, name: String, path: String, arr: JsonArray): String {
        val here = if (path.isEmpty()) "(root)" else path
        val entries = arr.mapNotNull { it as? JsonObject }
            .map { o ->
                val type = o["type"]?.jsonPrimitive?.content ?: "?"
                val entryName = o["name"]?.jsonPrimitive?.content ?: "?"
                val size = o["size"]?.jsonPrimitive?.int ?: 0
                Triple(type, entryName, size)
            }
            .sortedWith(compareBy({ if (it.first == "dir") 0 else 1 }, { it.second.lowercase() }))

        if (entries.isEmpty()) return "$owner/$name : $here is empty."

        val lines = entries.joinToString("\n") { (type, entryName, size) ->
            if (type == "dir") "[dir]  $entryName" else "[file] $entryName ($size B)"
        }
        return "$owner/$name : listing of $here\n$lines"
    }

    private fun formatFile(obj: JsonObject): ToolResult {
        val type = obj["type"]?.jsonPrimitive?.content
        if (type != "file") {
            return ToolResult(toAgent = "Error: path is not a file (type=$type).")
        }
        val filePath = obj["path"]?.jsonPrimitive?.content ?: "?"
        val size = obj["size"]?.jsonPrimitive?.int ?: 0
        val encoding = obj["encoding"]?.jsonPrimitive?.content
        val rawContent = obj["content"]?.jsonPrimitive?.content.orEmpty()

        if (encoding != "base64" || rawContent.isBlank()) {
            val download = obj["download_url"]?.jsonPrimitive?.content
            return ToolResult(
                toAgent = "File '$filePath' ($size B) is too large to inline or is binary. " +
                    (download?.let { "Download: $it" } ?: "No inline content available."),
            )
        }

        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(rawContent), Charsets.UTF_8)
        }.getOrElse {
            return ToolResult(toAgent = "Error decoding '$filePath': ${it.message}")
        }

        val capped = if (decoded.length > MAX_FILE_CHARS) {
            decoded.take(MAX_FILE_CHARS) + "\n... [truncated, ${decoded.length - MAX_FILE_CHARS} more chars]"
        } else {
            decoded
        }
        return ToolResult(toAgent = "File '$filePath' ($size B):\n$capped")
    }

    private fun errorFor(resp: HttpResponse<String>, repo: String, path: String): String = when (resp.statusCode()) {
        404 -> "Error: '$repo' or path '${path.ifEmpty { "(root)" }}' not found (404)."
        403 -> "Error: GitHub returned 403 (likely rate limited; set GITHUB_TOKEN to raise the limit)."
        else -> "Error: GitHub returned ${resp.statusCode()}: ${resp.body().take(300)}"
    }

    private companion object {
        const val MAX_FILE_CHARS = 20_000
    }
}
