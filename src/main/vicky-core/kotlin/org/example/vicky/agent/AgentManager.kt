package org.example.vicky.agent

import java.util.concurrent.ConcurrentHashMap

object AgentManager {
    private val byId = ConcurrentHashMap<String, Agent>()

    fun register(agent: Agent): Agent {
        require(byId.putIfAbsent(agent.id, agent) == null) { "Agent id 已存在: ${agent.id}" }
        return agent
    }

    fun unregister(id: String): Agent? = byId.remove(id)

    fun get(id: String): Agent? = byId[id]

    fun getByName(name: String): Agent? = byId.values.firstOrNull { it.name == name }

    fun all(): List<Agent> = byId.values.toList()

    fun clear() {
        byId.values.forEach { runCatching { it.close() } }
        byId.clear()
    }
}
