package org.example.vicky.channel

import net.mamoe.mirai.Bot
import org.example.vicky.agent.Agent
import org.example.vicky.agent.AgentConfig
import org.example.vicky.io.MessageSink
import org.example.vicky.io.OutboundMessage
import org.example.vicky.tool.ToolAuthorizer
import top.mrxiaom.overflow.BotBuilder

//TODO 未完成

class OneBot(var agentConfig: AgentConfig, var url: String, var token: String) {
    var usePrintLog = false

    val adminList = mutableSetOf<String>()
    val adminToolList = mutableSetOf<String>()

    var bot: Bot? = null

    val consoleAgent = ConsoleAgent(agentConfig, usePrintLog, adminList, adminToolList)

    suspend fun connect(): Boolean {
        bot = BotBuilder.positive(url).token(token).connect()
        return bot != null
    }

    class ConsoleAgent(config: AgentConfig, printLog: Boolean, adminList: Set<String>, adminToolList: Set<String>) : Agent(config) {
        override val sink = MessageSink { out ->
            if (!printLog) return@MessageSink
            when (out) {
                is OutboundMessage.AgentReply -> println("[Agent]" + out.content)
                is OutboundMessage.ToolReply -> println("[Tool:${out.toolName}]" + out.content)
                is OutboundMessage.Debug -> println("[Debug]" + out.content)
                is OutboundMessage.Think -> println("[Think]" + out.content)
            }
        }

        override val authorizer = ToolAuthorizer { userId, toolName ->
            if (adminToolList.contains(toolName)) adminList.contains(userId) else false
        }
    }

}