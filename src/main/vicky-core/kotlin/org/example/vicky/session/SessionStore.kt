package org.example.vicky.session

import com.aallam.openai.api.chat.ChatMessage

interface SessionStore {
    suspend fun loadHistory(conversationId: String): List<ChatMessage>
    suspend fun saveHistory(conversationId: String, history: List<ChatMessage>)
    suspend fun deleteHistory(conversationId: String)
    suspend fun listConversations(): List<String>
}
