package org.example.vicky.agent

import com.aallam.openai.api.model.ModelId

/**
 * Agent 运行时配置。
 *
 * @property model OpenAI / 兼容服务的模型 id (例如 "gpt-4o-mini" 或 "deepseek-chat")。
 * @property apiKey API key (可空仅用于 mock 测试)。
 * @property baseUrl 兼容 OpenAI 协议的自定义 host，例如 "https://api.deepseek.com/v1/"。null = 官方。
 * @property maxSteps 单次 receive 最多走多少轮 LLM 推理，防死循环。
 * @property mode 模式 1 (SILENT) 或模式 2 (VERBOSE)。
 * @property temperature 透传给 chat completion。
 * @property agentMd 基础系统提示文本 (人设/指令)，直接内联，不再读文件。
 */
data class AgentConfig(
    val model: ModelId,
    val apiKey: String,
    val baseUrl: String? = null,
    val maxSteps: Int = 8,
    val mode: AgentMode = AgentMode.SILENT,
    val temperature: Double? = null,
    val agentMd: String = "You are a helpful assistant.",
)
