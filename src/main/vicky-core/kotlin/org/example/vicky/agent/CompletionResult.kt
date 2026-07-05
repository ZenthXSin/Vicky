package org.example.vicky.agent

import com.aallam.openai.api.chat.ChatMessage

data class CompletionResult(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)
