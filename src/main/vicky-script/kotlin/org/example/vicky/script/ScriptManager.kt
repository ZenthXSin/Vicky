package org.example.vicky.script

import org.mozilla.javascript.Function
import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolRegistry
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
            exportsMap[fileName] = exports

            val bridge = ScriptToolBridge(engine, exports)
            loadedScripts[fileName]?.release()
            loadedScripts[fileName] = bridge
            return bridge
        } finally {
            synchronized(loadingScripts) {
                loadingScripts.remove(fileName)
            }
        }
    }

    /** 卸载脚本并从 registry 移除。onUnload 异常不会阻止卸载。 */
    fun unloadScript(fileName: String, registry: ToolRegistry) {
        loadedScripts.remove(fileName)?.let { bridge ->
            registry.unregister(bridge.name)
            // 调用 onUnload（安全容错）
            exportsMap.remove(fileName)?.let { exports ->
                callOnUnloadSafely(exports, fileName)
            }
            bridge.release()
        }
    }

    /** 重载脚本：卸载旧的，编译新的，注册到 registry。 */
    fun reloadScript(file: File, registry: ToolRegistry, options: TsCompilerOptions = TsCompilerOptions()) {
        val fileName = file.name
        unloadScript(fileName, registry)
        val bridge = loadScript(file, options)
        registry.register(bridge)
    }

    /** 加载脚本并注册到 registry。只有定义了 execute 的脚本才会注册为工具。 */
    fun loadAndRegister(file: File, registry: ToolRegistry, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val bridge = loadScript(file, options)
        val exports = exportsMap[file.name]
        if (exports?.executeFn != null) {
            registry.register(bridge)
        }
        return bridge
    }

    /** 获取所有已加载的脚本。 */
    fun loadedScripts(): Map<String, ScriptToolBridge> = loadedScripts.toMap()

    /** 按文件名查找已加载的脚本。 */
    fun get(fileName: String): ScriptToolBridge? = loadedScripts[fileName]

    /** 释放所有脚本资源。 */
    fun shutdown() {
        for ((_, bridge) in loadedScripts) bridge.release()
        loadedScripts.clear()
        exportsMap.clear()
        timeoutExecutor.shutdownNow()
    }

    // ─── 生命周期安全调用 ────────────────────────────────────

    /**
     * 安全调用 onLoad：在超时线程中执行，异常或超时返回 false。
     */
    private fun callOnLoadSafely(exports: ScriptExports, fileName: String): Boolean {
        val fn = exports.onLoadFn as? Function ?: return true
        val scope = exports.rhinoScope ?: return true
        val ctx = exports.rhinoContext ?: return true

        return try {
            val future = timeoutExecutor.submit {
                fn.call(ctx, scope, scope, emptyArray())
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
        val ctx = exports.rhinoContext ?: return

        try {
            fn.call(ctx, scope, scope, emptyArray())
        } catch (e: Exception) {
            println("[Vicky][script] onUnload 异常 (已忽略): $fileName: ${e.message}")
        }
    }
}
