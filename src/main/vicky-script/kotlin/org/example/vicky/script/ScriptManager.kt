package org.example.vicky.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.vicky.tool.ToolRegistry
import org.mozilla.javascript.Context
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.ConcurrentHashMap

/**
 * 脚本生命周期管理器：加载、热加载、注册到 ToolRegistry。
 */
object ScriptManager {

    private val engine = ScriptEngine()
    private val loadedScripts = ConcurrentHashMap<String, ScriptToolBridge>()
    private val config = ConcurrentHashMap<String, ScriptConfig>()
    private var scope: CoroutineScope? = null
    private var watchJob: kotlinx.coroutines.Job? = null

    fun loadAll(scriptsDir: File, registry: ToolRegistry, scriptConfig: ScriptConfig = ScriptConfig(scriptsDir)) {
        config["default"] = scriptConfig

        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
            println("[Vicky][script] 脚本目录已创建: ${scriptsDir.absolutePath}")
        }

        ClassAutoRegistry.init()

        val tsFiles: List<File> = scriptsDir.listFiles { f -> f.isFile && f.extension == "ts" }?.toList() ?: emptyList()
        var loaded = 0
        var failed = 0

        for (file in tsFiles) {
            try {
                val bridge = loadScript(file)
                registry.register(bridge)
                loaded++
                println("[Vicky][script] 已加载: ${bridge.name} (${file.name})")
            } catch (e: Exception) {
                failed++
                println("[Vicky][script] 加载失败: ${file.name}: ${e.message}")
            }
        }

        if (loaded > 0 || failed > 0) {
            println("[Vicky][script] 加载完成: $loaded 成功 / $failed 失败 (共 ${tsFiles.size} 个脚本)")
        }
    }

    fun loadScript(file: File): ScriptToolBridge {
        val tsSource = file.readText(Charsets.UTF_8)
        val fileName = file.name
        val options = config["default"]?.tsCompilerOptions ?: TsCompilerOptions()

        val jsSource = engine.compileTs(tsSource, fileName, options)
        val exports = engine.executeScript(jsSource, fileName)
        val bridge = ScriptToolBridge(engine, exports)

        loadedScripts[fileName]?.release()
        loadedScripts[fileName] = bridge
        return bridge
    }

    fun unloadScript(fileName: String, registry: ToolRegistry) {
        loadedScripts.remove(fileName)?.let { bridge ->
            registry.unregister(bridge.name)
            bridge.release()
            println("[Vicky][script] 已卸载: ${bridge.name} ($fileName)")
        }
    }

    fun reloadScript(file: File, registry: ToolRegistry) {
        val fileName = file.name
        unloadScript(fileName, registry)
        try {
            val bridge = loadScript(file)
            registry.register(bridge)
            println("[Vicky][script] 已重载: ${bridge.name} ($fileName)")
        } catch (e: Exception) {
            println("[Vicky][script] 重载失败: $fileName: ${e.message}")
        }
    }

    fun startWatcher(scriptsDir: File, registry: ToolRegistry) {
        stopWatcher()
        val scriptConfig = config["default"] ?: ScriptConfig(scriptsDir)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        watchJob = scope!!.launch {
            val watchService = FileSystems.getDefault().newWatchService()
            val dirPath = scriptsDir.toPath()
            try {
                dirPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
                println("[Vicky][script] 热加载监控已启动: ${scriptsDir.absolutePath}")

                while (true) {
                    val key = watchService.take()
                    val events = mutableMapOf<String, Long>()
                    for (event in key.pollEvents()) {
                        val fileName = (event.context() as? Path)?.toString() ?: continue
                        if (!fileName.endsWith(".ts")) continue
                        events[fileName] = System.currentTimeMillis()
                    }
                    key.reset()

                    if (events.isNotEmpty()) {
                        delay(scriptConfig.watchIntervalMs)
                        for ((fileName, _) in events) {
                            val file = File(scriptsDir, fileName)
                            if (file.exists()) reloadScript(file, registry)
                            else unloadScript(fileName, registry)
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                println("[Vicky][script] 文件监控异常: ${e.message}")
            } finally {
                watchService.close()
            }
        }
    }

    fun stopWatcher() {
        watchJob?.cancel()
        watchJob = null
        scope?.cancel()
        scope = null
    }

    fun loadedScripts(): Map<String, ScriptToolBridge> = loadedScripts.toMap()

    fun shutdown() {
        stopWatcher()
        for ((_, bridge) in loadedScripts) bridge.release()
        loadedScripts.clear()
    }
}
