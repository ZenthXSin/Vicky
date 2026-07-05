package org.example.vicky.vibe.orchestrator

import org.example.vicky.vibe.pipeline.StageOutput
import org.example.vicky.vibe.turn.VibeTurnResult

object VibeResultAdapter {
    fun toStageOutput(result: VibeTurnResult): StageOutput {
        val output = buildString {
            result.assistantReply?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (result.error != null) {
                if (isNotEmpty()) appendLine().appendLine()
                append("Error: ").append(result.error)
            }
            if (result.toolUses.isNotEmpty()) {
                if (isNotEmpty()) appendLine().appendLine()
                appendLine("## Tool Uses")
                for (toolUse in result.toolUses) {
                    val marker = if (toolUse.success) "✓" else "✗"
                    appendLine("- $marker ${toolUse.name} (${toolUse.id})")
                }
            }
        }.trimEnd()

        return StageOutput(
            summary = when {
                result.error != null -> "Vibe failed: ${result.error.take(80)}"
                result.assistantReply.isNullOrBlank() -> "Vibe completed without final text"
                else -> result.assistantReply.lineSequence().firstOrNull()?.take(100) ?: "Vibe completed"
            },
            output = output,
            pass = result.success,
        )
    }
}
