package org.example.vicky.script

import org.mozilla.javascript.Context
import kotlinx.coroutines.cancel
import org.mozilla.javascript.Function
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.command.CommandRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 脚本管理器：提供脚本的编译、加载、卸载、重载方法。
 *
 * 支持生命周期钩子 onLoad / onUnload，含安全机制：
 * - 循环依赖检测
 * - onLoad 超时（10 秒）
 * - onLoad 失败 → 脚本不注册
 * - onUnload 异常 → 强制继续卸载
 */
object ScriptManager {

    private val engine = ScriptEngine()
    private val loadedScripts = ConcurrentHashMap<String, ScriptToolBridge>()
    private val exportsMap = ConcurrentHashMap<String, ScriptExports>()
    private val loadedCommands = ConcurrentHashMap<String, List<ScriptCommandBridge>>()

    /** 正在加载中的脚本（循环依赖检测）。 */
    private val loadingScripts = mutableSetOf<String>()

    /** onLoad 超时线程池。 */
    private val timeoutExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "script-onload-${r.hashCode()}").apply { isDaemon = true }
    }

    private const val ONLOAD_TIMEOUT_SECONDS = 10L

    /** 编译并执行 TS 文件，返回 ScriptToolBridge（即 Tool）。不自动注册到 registry。 */
    fun loadScript(file: File, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val tsSource = file.readText(Charsets.UTF_8)
        val fileName = file.name
        return loadScriptInternal(fileName) { engine.compileTs(tsSource, fileName, options) }
    }

    /** 编译并执行宿主提供的 TS 内容。脚本名同时作为加载标识和默认工具名。 */
    fun loadScript(
        scriptName: String,
        scriptContent: String,
        options: TsCompilerOptions = TsCompilerOptions(),
    ): ScriptToolBridge {
        val normalizedName = normalizeScriptName(scriptName)
        return loadScriptFromSource(scriptContent, normalizedName, options)
    }

    /** 编译并执行 TS 源码字符串，返回 ScriptToolBridge。 */
    fun loadScriptFromSource(tsSource: String, fileName: String, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        return loadScriptInternal(fileName) { engine.compileTs(tsSource, fileName, options) }
    }

    private fun loadScriptInternal(fileName: String, compile: () -> String): ScriptToolBridge {
        // 循环依赖检测
        synchronized(loadingScripts) {
            if (fileName in loadingScripts) {
                throw ScriptException("Circular dependency detected for '$fileName'")
            }
            loadingScripts.add(fileName)
        }

        try {
            val jsSource = compile()
            val exports = engine.executeScript(jsSource, fileName)

            // 如果有 onLoad 钩子，在超时线程中调用
            if (exports.onLoadFn != null) {
                val success = callOnLoadSafely(exports, fileName)
                if (!success) {
                    throw ScriptException("onLoad failed for '$fileName'")
                }
            }

            exports.loaded = true
            val bridge = ScriptToolBridge(engine, exports)

            val previousExports = exportsMap.put(fileName, exports)
            val previousBridge = loadedScripts.put(fileName, bridge)
            previousExports?.let { old ->
                callOnUnloadSafely(old, fileName)
                old.coroutineScope.cancel()
            }
            previousBridge?.release()
            return bridge
        } finally {
            synchronized(loadingScripts) {
                loadingScripts.remove(fileName)
            }
        }
    }

    /** 卸载脚本并从 registry 移除。onUnload 异常不会阻止卸载。 */
    fun unloadScript(fileName: String, registry: ToolRegistry, commandRegistry: CommandRegistry? = null) {
        loadedCommands.remove(fileName)?.forEach { commandRegistry?.unregister(it.name) }
        loadedScripts.remove(fileName)?.let { bridge ->
            registry.unregister(bridge.name)
            // 调用 onUnload（安全容错）
            exportsMap.remove(fileName)?.let { exports ->
                callOnUnloadSafely(exports, fileName)
                // 取消脚本的协程作用域，终止所有后台协程
                exports.coroutineScope.cancel()
            }
            bridge.release()
        }
    }

    /** 重载脚本：卸载旧的，编译新的，注册到 registry。 */
    fun reloadScript(file: File, registry: ToolRegistry, commandRegistry: CommandRegistry? = null, options: TsCompilerOptions = TsCompilerOptions()) {
        val fileName = file.name
        unloadScript(fileName, registry, commandRegistry)
        val bridge = loadScript(file, options)
        registry.register(bridge)
        registerScriptCommands(file.name, commandRegistry)
    }

    /** 使用宿主提供的脚本内容重载并注册脚本。 */
    fun reloadScript(
        scriptName: String,
        scriptContent: String,
        registry: ToolRegistry,
        commandRegistry: CommandRegistry? = null,
        options: TsCompilerOptions = TsCompilerOptions(),
    ) {
        loadAndRegister(scriptName, scriptContent, registry, commandRegistry, options)
    }

    /** 加载脚本并注册到 registry。只有定义了 execute 的脚本才会注册为工具；定义了 commands 的脚本会注册命令。 */
    fun loadAndRegister(file: File, registry: ToolRegistry, commandRegistry: CommandRegistry? = null, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val bridge = loadScript(file, options)
        val exports = exportsMap[file.name]
        if (exports?.executeFn != null) {
            registry.register(bridge)
        }
        registerScriptCommands(file.name, commandRegistry)
        return bridge
    }

    /** 加载宿主提供的脚本内容，并注册其工具和命令导出。 */
    fun loadAndRegister(
        scriptName: String,
        scriptContent: String,
        registry: ToolRegistry,
        commandRegistry: CommandRegistry? = null,
        options: TsCompilerOptions = TsCompilerOptions(),
    ): ScriptToolBridge {
        val normalizedName = normalizeScriptName(scriptName)
        val previousToolName = loadedScripts[normalizedName]?.name
        val previousCommands = loadedCommands[normalizedName].orEmpty()
        val bridge = loadScript(normalizedName, scriptContent, options)

        previousToolName?.let(registry::unregister)
        previousCommands.forEach { commandRegistry?.unregister(it.name) }
        loadedCommands.remove(normalizedName)

        val exports = exportsMap[normalizedName]
        if (exports?.executeFn != null) {
            registry.register(bridge)
        }
        registerScriptCommands(normalizedName, commandRegistry)
        return bridge
    }

    private fun normalizeScriptName(scriptName: String): String {
        val normalized = scriptName.trim()
        require(normalized.isNotEmpty()) { "scriptName must not be blank" }
        return normalized
    }

    private fun registerScriptCommands(fileName: String, commandRegistry: CommandRegistry?) {
        if (commandRegistry == null) return
        val exports = exportsMap[fileName] ?: return
        if (exports.commandsDef.isEmpty()) return
        val bridges = exports.commandsDef.mapNotNull { def ->
            runCatching { ScriptCommandBridge(exports, def) }
                .onFailure { e -> println("[Vicky][script] 命令注册失败 ($fileName): ${e.message}") }
                .getOrNull()
        }
        bridges.forEach { commandRegistry.register(it) }
        loadedCommands[fileName] = bridges
    }

    /** 获取所有已加载的脚本。 */
    fun loadedScripts(): Map<String, ScriptToolBridge> = loadedScripts.toMap()

    /** 打印当前脚本统计日志。 */
    fun logStats() {
        val total = loadedScripts.size
        println("[Vicky][script] 加载完成: $total 个脚本已加载")
    }

    /** 按文件名查找已加载的脚本。 */
    fun get(fileName: String): ScriptToolBridge? = loadedScripts[fileName]

    /** 释放所有脚本资源。 */
    fun shutdown() {
        for ((fileName, bridge) in loadedScripts) {
            exportsMap[fileName]?.let { exports ->
                callOnUnloadSafely(exports, fileName)
                exports.coroutineScope.cancel()
            }
            bridge.release()
        }
        loadedScripts.clear()
        exportsMap.clear()
        loadedCommands.clear()
        timeoutExecutor.shutdownNow()
    }

    // ─── 生命周期安全调用 ────────────────────────────────────

    /**
     * 安全调用 onLoad：在超时线程中执行，异常或超时返回 false。
     */
    private fun callOnLoadSafely(exports: ScriptExports, fileName: String): Boolean {
        val fn = exports.onLoadFn as? Function ?: return true
        val scope = exports.rhinoScope ?: return true

        return try {
            val future = timeoutExecutor.submit {
                // Rhino Context 是 ThreadLocal，executor 线程必须 enter 自己的 Context
                val threadCtx = Context.enter()
                try {
                    fn.call(threadCtx, scope, scope, emptyArray())
                } finally {
                    Context.exit()
                }
            }
            future.get(ONLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (e: java.util.concurrent.TimeoutException) {
            println("[Vicky][script] onLoad 超时 (${ONLOAD_TIMEOUT_SECONDS}s): $fileName")
            false
        } catch (e: Exception) {
            println("[Vicky][script] onLoad 异常: $fileName: ${e.message}")
            false
        }
    }

    /**
     * 安全调用 onUnload：异常只打印警告，不阻止卸载。
     */
    private fun callOnUnloadSafely(exports: ScriptExports, fileName: String) {
        val fn = exports.onUnloadFn as? Function ?: return
        val scope = exports.rhinoScope ?: return

        try {
            val ctx = Context.enter()
            try {
                fn.call(ctx, scope, scope, emptyArray())
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            println("[Vicky][script] onUnload 异常 (已忽略): $fileName: ${e.message}")
        }
    }
}
