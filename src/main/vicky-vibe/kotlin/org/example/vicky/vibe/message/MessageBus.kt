package org.example.vicky.vibe.message

import org.example.vicky.vibe.role.AgentRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

interface MessageBus {
    suspend fun send(msg: AgentMessage)
    suspend fun receive(role: AgentRole): AgentMessage
    fun tryReceive(role: AgentRole): AgentMessage?
}

class InMemoryMessageBus : MessageBus {
    private val queues = ConcurrentHashMap<AgentRole, LinkedBlockingQueue<AgentMessage>>()

    private fun queue(role: AgentRole): LinkedBlockingQueue<AgentMessage> =
        queues.getOrPut(role) { LinkedBlockingQueue() }

    override suspend fun send(msg: AgentMessage) {
        queue(msg.to).put(msg)
    }

    override suspend fun receive(role: AgentRole): AgentMessage {
        return queue(role).take()
    }

    override fun tryReceive(role: AgentRole): AgentMessage? {
        return queue(role).poll()
    }
}
