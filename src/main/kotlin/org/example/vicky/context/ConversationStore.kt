package org.example.vicky.context

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import java.util.concurrent.ConcurrentHashMap

/** 内存级会话历史。无持久化，进程退出即丢失。支持 LRU 淘汰与单会话消息上限。 */
class ConversationStore(
    private val maxConversations: Int = 500,
    private val maxMessages: Int = 200,
) {
    private val histories = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val accessOrder = ConcurrentHashMap<String, Long>()

    fun history(conversationId: String): MutableList<ChatMessage> {
        accessOrder[conversationId] = System.currentTimeMillis()
        val list = histories.getOrPut(conversationId) { mutableListOf() }
        evictIfNeeded()
        return list
    }

    fun addMessage(conversationId: String, message: ChatMessage) {
        val list = history(conversationId)
        synchronized(list) {
            list.add(message)
            trimToLimit(list)
        }
    }

    fun clear(conversationId: String) {
        histories[conversationId]?.let { list ->
            synchronized(list) { list.clear() }
        }
        histories.remove(conversationId)
        accessOrder.remove(conversationId)
    }

    /** 裁剪指定会话的消息到上限。供外部在批量添加消息后调用。 */
    fun trimIfNeeded(conversationId: String) {
        val list = histories[conversationId] ?: return
        synchronized(list) { trimToLimit(list) }
    }

    private fun evictIfNeeded() {
        if (histories.size <= maxConversations) return
        val toEvict = histories.size - maxConversations
        val candidates = accessOrder.entries.sortedBy { it.value }.take(toEvict)
        for (entry in candidates) {
            histories.remove(entry.key)
            accessOrder.remove(entry.key)
        }
    }

    private fun trimToLimit(list: MutableList<ChatMessage>) {
        if (list.size <= maxMessages) return
        val systemMsg = list.firstOrNull { it.role == ChatRole.System }
        val nonSystem = list.filter { it.role != ChatRole.System }
        val keep = maxMessages - (if (systemMsg != null) 1 else 0)
        list.clear()
        if (systemMsg != null) list.add(systemMsg)
        list.addAll(nonSystem.takeLast(keep))
    }
}
