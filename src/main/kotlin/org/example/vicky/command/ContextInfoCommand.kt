package org.example.vicky.command

import org.example.vicky.agent.Agent

object ContextInfoCommand {

    fun create(agent: Agent): Command = command(
        name = "ctx",
        description = "上下文信息: /ctx [compact|clear]",
    ) { ctx, args ->
        when (args.trim()) {
            "compact" -> {
                agent.compactContext(ctx.conversationId)
                CommandResult(reply = "已压缩旧工具调用轮次。")
            }
            "clear" -> {
                agent.clearContext(ctx.conversationId)
                CommandResult(reply = "已清空上下文。")
            }
            else -> CommandResult(reply = agent.contextReport(ctx.conversationId))
        }
    }
}
