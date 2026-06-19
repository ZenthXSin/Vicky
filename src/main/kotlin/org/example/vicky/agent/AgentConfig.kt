package org.example.vicky.agent

import com.aallam.openai.api.model.ModelId

/**
 * Agent 运行时配置。
 *
 * @property model OpenAI / 兼容服务的模型 id (例如 "gpt-4o-mini" 或 "deepseek-chat")。
 * @property apiKey API key (可空仅用于 mock 测试)。
 * @property baseUrl 兼容 OpenAI 协议的自定义 host，例如 "https://api.deepseek.com/v1/"。null = 官方。
 * @property maxSteps 单次 receive 最多走多少轮 LLM 推理，防死循环。
 * @property maxMemoryRounds 最多保留多少轮用户消息（1轮 = user + assistant），超过则截断旧消息。0 = 不限制。
 * @property maxContextLength 上下文总字符数上限，超过则触发 LLM 压缩生成摘要。0 = 不限制。
 * @property mode 模式 1 (SILENT) 或模式 2 (VERBOSE)。
 * @property temperature 透传给 chat completion。
 * @property agentMd 基础系统提示文本 (人设/指令)，直接内联，不再读文件。
 * @property debug 打开后输出框架运行日志 (每轮推理 / 工具调用) 及底层 HTTP 日志。
 * @property think 打开后把 agent 每轮的中间思考文本 (调用工具前的 content) 打到日志。
 * @property embedding 语义模型配置；null = 未启用。内置 / 外置互斥，见 [EmbeddingConfig]。
 */
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
    val builtinTools: Boolean = true,
    val embedding: EmbeddingConfig? = null,
    // Qdrant 配置
    val qdrantHost: String? = null,
    val qdrantGrpcPort: Int = 6334,
    val qdrantHttpPort: Int = 6333,
    // 记忆配置
    val memoryEnabled: Boolean = false,
    val memoryTopK: Int = 5,
    val memoryTokenBudget: Int = 800,
    val memoryMaxPerUser: Int = 500,
    val memoryExpiryDays: Int = 90,
    val memoryRawRetentionDays: Int = 30,
    val memoryDistilledRetentionDays: Int = 7,
    val memoryCollection: String = "vicky_memories",
    val memoryRawCollection: String = "vicky_memories_raw",
    // 蒸馏配置
    val distillationEnabled: Boolean = true,
    val distillationSchedule: String = "0 2 * * *",
    val distillationMaxConversations: Int = 10,
    val distillationTemperature: Double = 0.1,
    val distillationMaxTokens: Int = 1000,
    // 文件索引配置
    val fileIndexEnabled: Boolean = false,
    val fileIndexCollection: String = "vicky_files",
    val fileIndexChunkSize: Int = 500,
    val fileIndexChunkOverlap: Int = 50,
    val fileIndexIgnorePatterns: List<String> = listOf(".git", ".gradle", "build", "node_modules"),
    val fileIndexPaths: List<String> = emptyList(),
    val fileIndexAutoIndexOnStart: Boolean = true,
)