package org.example.vicky.vibe.tool

import com.aallam.openai.api.chat.ChatMessage

class VibeToolUseResult(
    val messages: List<ChatMessage>,
    val toolUses: List<VibeToolUse>,
    val endTurn: Boolean,
)
