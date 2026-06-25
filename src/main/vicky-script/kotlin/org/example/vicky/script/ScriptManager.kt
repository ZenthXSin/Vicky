package org.example.vicky.script

import org.example.vicky.tool.Tool
import org.example.vicky.tool.ToolRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 脚本管理器：提供脚本的编译、加载、卸载、重载方法。
 *
 * 不自动扫描目录、不自动监控文件。调用方自行决定何时加载和如何管理脚本。
 */
object ScriptManager {

    private val engine = ScriptEngine()
    private val loadedScripts = ConcurrentHashMap<String, ScriptToolBridge>()

    /** 编译并执行 TS 文件，返回 ScriptToolBridge（即 Tool）。不自动注册到 registry。 */
    fun loadScript(file: File, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val tsSource = file.readText(Charsets.UTF_8)
        val fileName = file.name

        val jsSource = engine.compileTs(tsSource, fileName, options)
        val exports = engine.executeScript(jsSource, fileName)
        val bridge = ScriptToolBridge(engine, exports)

        loadedScripts[fileName]?.release()
        loadedScripts[fileName] = bridge
        return bridge
    }

    /** 编译并执行 TS 源码字符串，返回 ScriptToolBridge。 */
    fun loadScriptFromSource(tsSource: String, fileName: String, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val jsSource = engine.compileTs(tsSource, fileName, options)
        val exports = engine.executeScript(jsSource, fileName)
        val bridge = ScriptToolBridge(engine, exports)

        loadedScripts[fileName]?.release()
        loadedScripts[fileName] = bridge
        return bridge
    }

    /** 卸载脚本并从 registry 移除。 */
    fun unloadScript(fileName: String, registry: ToolRegistry) {
        loadedScripts.remove(fileName)?.let { bridge ->
            registry.unregister(bridge.name)
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

    /** 加载脚本并注册到 registry。 */
    fun loadAndRegister(file: File, registry: ToolRegistry, options: TsCompilerOptions = TsCompilerOptions()): ScriptToolBridge {
        val bridge = loadScript(file, options)
        registry.register(bridge)
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
    }
}
