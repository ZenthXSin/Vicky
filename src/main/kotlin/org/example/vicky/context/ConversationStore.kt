package org.example.vicky.context

import com.aallam.openai.api.chat.ChatMessage
import java.util.concurrent.ConcurrentHashMap

/** 内存级会话历史。无持久化，进程退出即丢失。 */
class ConversationStore {
    private val histories = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    fun history(conversationId: String): MutableList<ChatMessage> =
        histories.getOrPut(conversationId) { mutableListOf() }

    fun clear(conversationId: String) {
        histories.remove(conversationId)
    }
}
