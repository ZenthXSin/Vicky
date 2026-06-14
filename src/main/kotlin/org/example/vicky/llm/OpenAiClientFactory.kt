package org.example.vicky.llm

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import org.example.vicky.agent.AgentConfig
import kotlin.time.Duration.Companion.seconds

/** 工厂方法，集中处理 baseUrl / timeout / 兼容 OpenAI 协议的第三方端点。 */
object OpenAiClientFactory {
    fun create(config: AgentConfig): OpenAI {
        // OpenAIHost 用 baseUrl 解析相对路径，必须以 '/' 结尾，否则末段会被替换掉
        // (".../v1" + "chat/completions" -> ".../chat/completions")。
        val host = config.baseUrl
            ?.let { if (it.endsWith("/")) it else "$it/" }
            ?.let { OpenAIHost(baseUrl = it) }
            ?: OpenAIHost.OpenAI
        return OpenAI(
            OpenAIConfig(
                token = config.apiKey,
                timeout = Timeout(socket = 60.seconds),
                host = host,
                logging = LoggingConfig(logLevel = if (config.debug) LogLevel.All else LogLevel.None),
            )
        )
    }
}
