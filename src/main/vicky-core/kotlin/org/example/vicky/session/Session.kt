package org.example.vicky.session

import com.aallam.openai.api.chat.ChatMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.vicky.agent.Agent
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink

class Session(
    val conversationId: String,
    private val store: SessionStore?,
    private val agent: Agent,
) {
    val mutex = Mutex()
    @Volatile var historyLoaded = false

    suspend fun receive(
        msg: InboundMessage,
        replySink: MessageSink? = null,
        clearContextAfter: Boolean = false,
    ) = mutex.withLock {
        agent.receiveInternal(msg, replySink, clearContextAfter)
    }

    suspend fun loadHistory(): List<ChatMessage> =
        store?.loadHistory(conversationId) ?: emptyList()

    suspend fun flush(history: List<ChatMessage>) {
        store?.saveHistory(conversationId, history)
    }

    suspend fun delete() {
        store?.deleteHistory(conversationId)
    }
}
