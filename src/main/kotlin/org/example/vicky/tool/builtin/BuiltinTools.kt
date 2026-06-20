package org.example.vicky.tool.builtin

import org.example.vicky.file.FileIndexService
import org.example.vicky.generated.ToolRegistry
import org.example.vicky.memory.DistillationScheduler
import org.example.vicky.memory.MemoryStore
import org.example.vicky.tool.Tool

/** 框架内置工具集合。Agent 在 [config.builtinTools] 打开时自动注册。 */
object BuiltinTools {
    fun all(
        baseDir: java.io.File = java.io.File(System.getProperty("user.dir")),
        memoryStore: MemoryStore? = null,
        fileIndexService: FileIndexService? = null,
        distillationScheduler: DistillationScheduler? = null,
    ): List<Tool> {
        BuiltinToolImpl.baseDir = baseDir
        BuiltinToolImpl.memoryStore = memoryStore
        BuiltinToolImpl.fileIndexService = fileIndexService
        BuiltinToolImpl.distillationScheduler = distillationScheduler
        return ToolRegistry.tools("builtin")
    }
}
