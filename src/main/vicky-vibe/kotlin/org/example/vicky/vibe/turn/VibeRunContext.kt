package org.example.vicky.vibe.turn

import com.aallam.openai.api.chat.ChatMessage

class VibeRunContext(
    val history: MutableList<ChatMessage>,
    var promptTokens: Int = 0,
    var completionTokens: Int = 0,
)
