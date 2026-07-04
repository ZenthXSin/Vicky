package org.example.vicky.agent

import com.aallam.openai.api.model.ModelId

/**
 * Agent 运行时配置（核心）。
 *
 * 语义记忆、向量存储、Embedding、文件索引等扩展配置由子类自行管理。
 */
data class McpServerConfig(
    val name: String,
    val transport: String = "stdio", // "stdio" 或 "http"
    val command: String = "",        // stdio 模式：可执行命令
    val args: List<String> = emptyList(), // stdio 模式：命令参数
    val url: String = "",            // http 模式：服务器地址
)

data class AgentConfig(
    val model: ModelId,
    val apiKey: String,
    val baseUrl: String? = null,
    val maxSteps: Int = 8,
    val maxMemoryRounds: Int = 50,
    val maxContextLength: Int = 0,
    val mode: AgentMode = AgentMode.SILENT,
    val temperature: Double? = null,
    val agentMd: String = "You are a helpful assistant.",
    val debug: Boolean = false,
    val think: Boolean = false,
    val streaming: Boolean = true,
    val builtinTools: Boolean = true,
    val toolStates: Map<String, Boolean> = emptyMap(),
    // MCP 服务器配置
    val mcpServers: List<McpServerConfig> = emptyList(),
    // 会话存储限制
    val conversationStoreMaxConversations: Int = 500,
    val conversationStoreMaxMessages: Int = 200,
    // 消息缓冲区限制
    val messageBufferMaxGlobalEntries: Int = 10000,
    val messageBufferRawTruncate: Int = 500,
    val name: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    // LLM 调用超时（毫秒）与重试次数
    val llmTimeoutMs: Long = 120_000L,
    val llmMaxRetries: Int = 2,
    val lazyToolSchema: Boolean = true,
)
