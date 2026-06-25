package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.script.ScriptManager
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import java.io.File

/**
 * 脚本管理工具：load / unload / reload / list。
 */
class ManageScriptsTool(
    private val scriptsDir: File,
    private val registry: ToolRegistry,
) : Tool() {
    override val name = "manage_scripts"
    override val description =
        "Manage TypeScript scripts. " +
            "Use action='list' to see loaded scripts. " +
            "Use action='load' with 'name' to load a .ts file from the scripts directory. " +
            "Use action='unload' with 'name' to unload a loaded script. " +
            "Use action='reload' with 'name' to reload a script. " +
            "Use action='load_all' to load all .ts files in the scripts directory."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("load"))
                    add(JsonPrimitive("unload"))
                    add(JsonPrimitive("reload"))
                    add(JsonPrimitive("load_all"))
                }
                put("description", "Operation: list / load / unload / reload / load_all.")
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Script filename (e.g. 'hello.ts'). Required for load / unload / reload.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
    }

    override suspend fun execute(userId: String, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "error: missing required parameter 'action'")
        return when (action) {
            "list" -> list()
            "load" -> load(args)
            "unload" -> unload(args)
            "reload" -> reload(args)
            "load_all" -> loadAll()
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be one of: list, load, unload, reload, load_all.")
        }
    }

    private fun list(): ToolResult {
        val scripts = ScriptManager.loadedScripts()
        if (scripts.isEmpty()) return ToolResult(toAgent = "No scripts loaded.")
        val sb = StringBuilder("Loaded scripts (${scripts.size}):\n")
        for ((fileName, bridge) in scripts) {
            sb.appendLine("  $fileName -> ${bridge.name} — ${bridge.description}")
        }
        return ToolResult(toAgent = sb.toString().trimEnd())
    }

    private fun load(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")
        return try {
            val bridge = ScriptManager.loadAndRegister(file, registry)
            ToolResult(toAgent = "Loaded '${bridge.name}' from $name.", userReply = "已加载脚本: ${bridge.name}")
        } catch (e: Exception) {
            ToolResult(toAgent = "error loading '$name': ${e.message}")
        }
    }

    private fun unload(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        if (ScriptManager.get(name) == null) return ToolResult(toAgent = "error: script '$name' is not loaded")
        ScriptManager.unloadScript(name, registry)
        return ToolResult(toAgent = "Unloaded script '$name'.", userReply = "已卸载脚本: $name")
    }

    private fun reload(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")
        return try {
            ScriptManager.reloadScript(file, registry)
            val bridge = ScriptManager.get(name)
            ToolResult(toAgent = "Reloaded '${bridge?.name ?: name}'.", userReply = "已重载脚本: ${bridge?.name ?: name}")
        } catch (e: Exception) {
            ToolResult(toAgent = "error reloading '$name': ${e.message}")
        }
    }

    private fun loadAll(): ToolResult {
        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val tsFiles = scriptsDir.listFiles { f -> f.isFile && f.extension == "ts" }?.toList()
            ?: return ToolResult(toAgent = "No .ts files found in ${scriptsDir.absolutePath}")
        var loaded = 0
        var failed = 0
        val sb = StringBuilder()
        for (file in tsFiles) {
            try {
                val bridge = ScriptManager.loadAndRegister(file, registry)
                loaded++
                sb.appendLine("  [ok] ${file.name} -> ${bridge.name}")
            } catch (e: Exception) {
                failed++
                sb.appendLine("  [fail] ${file.name}: ${e.message}")
            }
        }
        return ToolResult(
            toAgent = "Loaded $loaded / ${tsFiles.size} scripts ($failed failed):\n${sb.toString().trimEnd()}",
            userReply = "已加载 $loaded 个脚本。",
        )
    }
}
