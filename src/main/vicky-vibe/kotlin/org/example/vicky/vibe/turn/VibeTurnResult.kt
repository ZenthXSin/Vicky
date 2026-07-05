package org.example.vicky.vibe.turn

import org.example.vicky.vibe.tool.VibeToolUse

class VibeTurnResult(
    val assistantReply: String?,
    val toolUses: List<VibeToolUse>,
    val promptTokens: Int,
    val completionTokens: Int,
    val success: Boolean,
    val error: String? = null,
    val stepCount: Int = 0,
)
