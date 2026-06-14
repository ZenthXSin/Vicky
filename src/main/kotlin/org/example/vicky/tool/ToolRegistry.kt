package org.example.vicky.tool

import java.util.concurrent.ConcurrentHashMap

/** 线程安全的工具注册表，支持运行时增/删/查。 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String): Tool? = tools.remove(name)

    operator fun get(name: String): Tool? = tools[name]

    fun snapshot(): List<Tool> = tools.values.toList()

    fun isEmpty(): Boolean = tools.isEmpty()
}
