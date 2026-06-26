package org.example.vicky.vibe.agent

import org.example.vicky.agent.AgentMode

data object VibeAgentMode : AgentMode() {
    override val name = "VIBE"
    override val toolsEnabled = true
    override val emitAgentText = true
    override val instructions =
        "You are a stage agent in a multi-stage pipeline. " +
            "Your plain-text replies ARE captured by the orchestrator. " +
            "Follow the Output Contract in the system prompt strictly. " +
            "Call tools when they help you fulfill your stage responsibility."
}
