package org.example.vicky.tool.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.vicky.script.ScriptEngine
import org.example.vicky.script.ScriptException
import org.example.vicky.script.ScriptManager
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolContext
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.tool.ToolResult
import java.io.File

// runScript 需要的额外导入
import com.aallam.openai.api.chat.ChatMessage
import org.example.vicky.agent.AgentMode
import org.example.vicky.context.ContextManager

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
            "Use action='load_all' to load all .ts files in the scripts directory. " +
            "Use action='create' with 'name' and 'content' to create a new script. " +
            "Use action='view' with 'name' to view a script's source code. " +
            "Use action='diagnose' with 'name' to compile and check a script without loading it (shows errors with line numbers). " +
            "Use action='run' with 'name' and 'args' to execute a script once without registering it as a tool."

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
                    add(JsonPrimitive("create"))
                    add(JsonPrimitive("view"))
                    add(JsonPrimitive("diagnose"))
                    add(JsonPrimitive("run"))
                }
                put("description", "Operation: list / load / unload / reload / load_all / create / view / diagnose / run.")
            }
            putJsonObject("name") {
                put("type", "string")
                put("description", "Script filename (e.g. 'hello.ts'). Required for load / unload / reload / create / view.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "TypeScript source code. Required for create action.")
            }
            putJsonObject("auto_load") {
                put("type", "boolean")
                put("description", "Whether to automatically load the script after creating. Default true.")
            }
            putJsonObject("args") {
                put("type", "object")
                put("description", "Arguments to pass to the script when using action='run'. Keys are parameter names.")
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
            "create" -> create(args)
            "view" -> view(args)
            "diagnose" -> diagnose(args)
            "run" -> runScript(args)
            else -> ToolResult(toAgent = "error: unknown action '$action'. Must be one of: list, load, unload, reload, load_all, create, view, diagnose, run.")
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

    private fun resolveFileName(name: String): String =
        if (name.endsWith(".ts")) name else "$name.ts"

    private fun load(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")
        return try {
            val bridge = ScriptManager.loadAndRegister(file, registry)
            ToolResult(toAgent = "Loaded '${bridge.name}' from $name.", userReply = "已加载脚本: ${bridge.name}")
        } catch (e: ScriptException) {
            ToolResult(toAgent = "[script error] $name: ${e.message}")
        } catch (e: Exception) {
            ToolResult(toAgent = "[unexpected error] loading '$name': ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun unload(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        if (ScriptManager.get(name) == null) return ToolResult(toAgent = "error: script '$name' is not loaded")
        ScriptManager.unloadScript(name, registry)
        return ToolResult(toAgent = "Unloaded script '$name'.", userReply = "已卸载脚本: $name")
    }

    private fun reload(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")
        return try {
            ScriptManager.reloadScript(file, registry)
            val bridge = ScriptManager.get(name)
            ToolResult(toAgent = "Reloaded '${bridge?.name ?: name}'.", userReply = "已重载脚本: ${bridge?.name ?: name}")
        } catch (e: ScriptException) {
            ToolResult(toAgent = "[script error] reloading '$name': ${e.message}")
        } catch (e: Exception) {
            ToolResult(toAgent = "[unexpected error] reloading '$name': ${e.javaClass.simpleName}: ${e.message}")
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

    private fun create(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult(toAgent = "error: missing required parameter 'content'")
        if (!name.endsWith(".ts")) return ToolResult(toAgent = "error: script filename must end with '.ts'")

        if (!scriptsDir.exists()) scriptsDir.mkdirs()
        val file = File(scriptsDir, name)
        if (file.exists()) return ToolResult(toAgent = "error: file '$name' already exists. Use action='reload' to update, or delete it first.")

        return try {
            file.writeText(content, Charsets.UTF_8)
            val autoLoad = args["auto_load"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            if (autoLoad) {
                try {
                    val bridge = ScriptManager.loadAndRegister(file, registry)
                    ToolResult(toAgent = "Created and loaded '${bridge.name}' ($name, ${content.toByteArray(Charsets.UTF_8).size} bytes).", userReply = "已创建并加载脚本: ${bridge.name}")
                } catch (e: ScriptException) {
                    ToolResult(toAgent = "Created '$name' but failed to load:\n[script error] ${e.message}", userReply = "已创建脚本 $name，但加载失败: ${e.message}")
                } catch (e: Exception) {
                    ToolResult(toAgent = "Created '$name' but failed to load:\n[${e.javaClass.simpleName}] ${e.message}", userReply = "已创建脚本 $name，但加载失败: ${e.message}")
                }
            } else {
                ToolResult(toAgent = "Created '$name' (${content.toByteArray(Charsets.UTF_8).size} bytes). Use action='load' to load it.", userReply = "已创建脚本: $name")
            }
        } catch (e: Exception) {
            ToolResult(toAgent = "error creating '$name': ${e.message}")
        }
    }

    private fun view(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")
        return try {
            val content = file.readText(Charsets.UTF_8)
            val loaded = ScriptManager.get(name)
            val status = if (loaded != null) "[loaded] ${loaded.name} — ${loaded.description}" else "[not loaded]"
            ToolResult(toAgent = "Script '$name' ($status):\n\n$content")
        } catch (e: Exception) {
            ToolResult(toAgent = "error reading '$name': ${e.message}")
        }
    }

    private fun diagnose(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")

        val sb = StringBuilder("--- diagnose: $name ---\n")
        val engine = ScriptEngine()

        // Step 1: 读取文件
        val source = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            sb.appendLine("[FAIL] 读取文件: ${e.message}")
            return ToolResult(toAgent = sb.toString())
        }
        sb.appendLine("[OK] 读取文件 (${source.toByteArray(Charsets.UTF_8).size} bytes)")

        // Step 2: 编译 TS → JS
        val jsSource = try {
            engine.compileTs(source, name)
        } catch (e: Exception) {
            sb.appendLine("[FAIL] 编译 TS → JS: ${e.message}")
            return ToolResult(toAgent = sb.toString())
        }
        sb.appendLine("[OK] 编译 TS → JS (${jsSource.toByteArray(Charsets.UTF_8).size} bytes)")

        // Step 3: 执行 JS 提取 exports
        val exports = try {
            engine.executeScript(jsSource, name)
        } catch (e: ScriptException) {
            sb.appendLine("[FAIL] 执行 JS: ${e.message}")
            return ToolResult(toAgent = sb.toString())
        } catch (e: Exception) {
            sb.appendLine("[FAIL] 执行 JS: ${e.javaClass.simpleName}: ${e.message}")
            return ToolResult(toAgent = sb.toString())
        }

        // Step 4: 检查 exports
        sb.appendLine("[OK] 执行 JS，提取导出:")
        sb.appendLine("  name = \"${exports.name}\"")
        sb.appendLine("  description = \"${exports.description}\"")
        sb.appendLine("  parameters = ${exports.parameters}")
        sb.appendLine("  execute = ${if (exports.executeFn != null) "function()" else "MISSING"}")

        if (exports.name.isBlank()) sb.appendLine("[WARN] name 为空")
        if (exports.executeFn == null) sb.appendLine("[ERROR] execute 函数缺失")

        // Step 5: 检查已加载状态
        val existing = ScriptManager.get(name)
        if (existing != null) {
            sb.appendLine("[INFO] 此脚本当前已加载为工具 '${existing.name}'")
        }

        sb.append("--- 诊断完成 ---")
        return ToolResult(toAgent = sb.toString())
    }

    private fun runScript(args: JsonObject): ToolResult {
        val rawName = args["name"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(toAgent = "error: missing required parameter 'name'")
        val name = resolveFileName(rawName)
        val file = File(scriptsDir, name)
        if (!file.exists()) return ToolResult(toAgent = "error: file '$name' not found in ${scriptsDir.absolutePath}")

        val engine = ScriptEngine()

        // 编译
        val source = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ToolResult(toAgent = "[read error] $name: ${e.message}")
        }

        val jsSource = try {
            engine.compileTs(source, name)
        } catch (e: Exception) {
            return ToolResult(toAgent = "[compile error] $name: ${e.message}")
        }

        // 执行提取 exports
        val exports = try {
            engine.executeScript(jsSource, name)
        } catch (e: ScriptException) {
            return ToolResult(toAgent = "[execute error] $name: ${e.message}")
        } catch (e: Exception) {
            return ToolResult(toAgent = "[execute error] $name: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 构建 args JsonObject
        val scriptArgs = args["args"] as? JsonObject ?: buildJsonObject {}

        // 构建 ToolContext（run 模式下用占位值）
        val dummyCtx = ToolContext(
            userId = "run",
            conversationId = "run",
            contextManager = object : ContextManager {
                override fun history(conversationId: String) = mutableListOf<ChatMessage>()
                override fun buildSystemPrompt(mode: AgentMode, tools: ToolRegistry) = ""
                override suspend fun ensureContextBudget(history: MutableList<ChatMessage>) {}
                override fun compactOldToolRounds(history: MutableList<ChatMessage>) {}
                override fun clear(conversationId: String) {}
                override fun trimIfNeeded(conversationId: String) {}
            },
            tools = registry,
        )

        // 调用 execute
        val result = engine.callExecuteSafe(exports, dummyCtx, scriptArgs)

        val sb = StringBuilder("--- run: $name ---\n")
        sb.appendLine("args: $scriptArgs")
        sb.appendLine("result:")
        sb.appendLine("  toAgent = ${result["toAgent"]}")
        if (result.containsKey("userReply")) sb.appendLine("  userReply = ${result["userReply"]}")
        if (result.containsKey("endTurn")) sb.appendLine("  endTurn = ${result["endTurn"]}")
        if (result.containsKey("error")) sb.appendLine("  [ERROR]")
        sb.append("--- done ---")

        engine.releaseExports(exports)
        return ToolResult(toAgent = sb.toString())
    }
}
