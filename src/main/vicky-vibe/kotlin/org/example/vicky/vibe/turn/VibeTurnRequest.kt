package org.example.vicky.vibe.turn

import org.example.vicky.agent.AgentConfig
import org.example.vicky.context.ContextManager
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.MessageSink
import org.example.vicky.tool.ToolAuthorizer
import org.example.vicky.tool.ToolRegistry
import org.example.vicky.vibe.task.TaskGraph

class VibeTurnRequest(
    val config: AgentConfig,
    val inbound: InboundMessage,
    val tools: ToolRegistry,
    val contextManager: ContextManager,
    val authorizer: ToolAuthorizer,
    val sink: MessageSink,
    val taskGraph: TaskGraph? = null,
    val buffer: Any? = null,
    val resetContext: Boolean = false,
)
