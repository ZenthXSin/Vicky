package org.example.vicky.agent

import com.aallam.openai.api.model.ModelId

/**
 * Agent 运行时配置。
 * @property model OpenAI / 兼容服务的模型 id，例如 `"gpt-4o-mini"`、`"deepseek-chat"`。
 *                 直接作为 `ModelId` 透传给 chat completion 接口。
 * @property apiKey 调用 LLM 用的 API Key。仅在 mock 测试场景下可为空字符串。
 * @property baseUrl 兼容 OpenAI 协议的自定义服务地址，例如 `"https://api.deepseek.com/v1/"`。
 *                   设为 `null` 表示走 OpenAI 官方端点。
 * @property maxSteps 单次 `receive` 内允许的最大 LLM 推理轮数，用于防止工具调用死循环。
 *                    达到上限后框架会强制终止并返回当前结果。
 * @property maxMemoryRounds 短期上下文窗口中最多保留的对话轮数。
 *                           此处 1 轮 = 1 条 user 消息 + 1 条 assistant 消息。
 *                           超出后按时间序截断最早的整轮消息。
 *                           设为 `0` 表示不限制条数（仍受 [maxContextLength] 约束）。
 * @property maxContextLength 上下文总字符数上限。超过该阈值时触发 LLM 摘要压缩，
 *                           将旧消息合并为一段摘要后再继续对话。
 *                           设为 `0` 表示不启用基于长度的摘要压缩。
 *                           [think] 打开时此项被忽略，上下文不做压缩，便于完整观察思考链。
 * @property mode 运行模式：[AgentMode.SILENT]（模式 1，静默）或 [AgentMode.VERBOSE]（模式 2，详尽输出）。
 * @property temperature 采样温度，透传给 chat completion。
 *                        设为 `null` 表示使用服务端默认值；值越大输出越发散，越小越确定。
 * @property agentMd 基础系统提示文本（人设 / 指令），以字符串内联方式提供，不再从文件读取。
 * @property debug 是否开启框架运行日志（每轮推理、工具调用）及底层 HTTP 日志。
 * @property think 是否将 Agent 每轮的中间思考文本（调用工具前的 content）输出到日志。
 *                 打开后同时关闭基于 [maxContextLength] 的上下文压缩。
 * @property streaming LLM 请求模式。`true` 走流式（`chatCompletions`），`false` 走一次性（`chatCompletion`）。
 *                      默认 `true`：连接更稳，长响应不易卡死；语义上与非流式等价（框架在 chunk 收齐后再拼装为一条 ChatMessage 供下游使用）。
 * @property builtinTools 是否注册框架内置工具集。设为 `false` 时 Agent 仅依赖外部注入的工具。
 * @property embedding 语义向量模型配置，用于记忆与文件索引的向量化。
 *                      设为 `null` 表示未启用语义能力，此时长期记忆与文件索引均不可用。
 *                      内置与外置 Embedding 互斥，详见 [EmbeddingConfig]。
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
    val streaming: Boolean = true,
    val builtinTools: Boolean = true,
    val toolStates: Map<String, Boolean> = emptyMap(),
    val embedding: EmbeddingConfig? = null,
    val qdrantHost: String? = null,
    val qdrantGrpcPort: Int = 6334,
    val qdrantHttpPort: Int = 6333,
    val memoryEnabled: Boolean = false,
    val memoryTopK: Int = 5,
    val memoryTokenBudget: Int = 800,
    val memoryMaxPerUser: Int = 500,
    val memoryExpiryDays: Int = 90,
    val memoryRawRetentionDays: Int = 30,
    val memoryDistilledRetentionDays: Int = 7,
    val memoryCollection: String = "vicky_memories",
    val memoryRawCollection: String = "vicky_memories_raw",
    val distillationEnabled: Boolean = true,
    val distillationSchedule: String = "0 2 * * *",
    val distillationMaxConversations: Int = 10,
    val distillationTemperature: Double = 0.1,
    val distillationMaxTokens: Int = 1000,
    val fileIndexEnabled: Boolean = false,
    val fileIndexCollection: String = "vicky_files",
    val fileIndexChunkSize: Int = 500,
    val fileIndexChunkOverlap: Int = 50,
    val fileIndexIgnorePatterns: List<String> = listOf(".git", ".gradle", "build", "node_modules", "config/tmp"),
    val fileIndexPaths: List<String> = emptyList(),
    val fileIndexAutoIndexOnStart: Boolean = true,
    // 会话存储限制
    val conversationStoreMaxConversations: Int = 500,
    val conversationStoreMaxMessages: Int = 200,
    // 消息缓冲区限制
    val messageBufferMaxGlobalEntries: Int = 10000,
    val messageBufferRawTruncate: Int = 500,
)
