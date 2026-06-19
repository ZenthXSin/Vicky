package org.example.vicky.memory

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.vicky.llm.EmbeddingClient

/**
 * 蒸馏器。从原始对话中提取关键信息，存入蒸馏记忆。
 */
class Distiller(
    private val openAi: OpenAI,
    private val embeddingClient: EmbeddingClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 蒸馏一组对话，返回提取的关键信息。
     */
    suspend fun distill(conversations: List<RawMemory>): List<Memory> {
        if (conversations.isEmpty()) return emptyList()

        val prompt = buildDistillationPrompt(conversations)
        val request = ChatCompletionRequest(
            model = ModelId("deepseek-v4-flash"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = DISTILLATION_SYSTEM_PROMPT),
                ChatMessage(role = ChatRole.User, content = prompt),
            ),
            temperature = 0.1,
        )

        val response = openAi.chatCompletion(request)
        val content = response.choices.firstOrNull()?.message?.content ?: return emptyList()

        return parseDistillationResult(content)
    }

    private fun buildDistillationPrompt(conversations: List<RawMemory>): String {
        val grouped = conversations.groupBy { Triple(it.userId, it.conversationId, it.turnIndex) }
        val sb = StringBuilder()

        for ((key, messages) in grouped) {
            val (userId, conversationId, turnIndex) = key
            sb.appendLine("=== 对话 (用户: $userId, 会话: $conversationId, 轮次: $turnIndex) ===")
            for (msg in messages.sortedBy { it.timestamp }) {
                val roleLabel = if (msg.role == "user") "用户" else "助手"
                sb.appendLine("$roleLabel: ${msg.content}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun parseDistillationResult(content: String): List<Memory> {
        return try {
            val jsonStr = content.trim().removePrefix("```json").removeSuffix("```").trim()
            val array = json.parseToJsonElement(jsonStr).jsonArray
            array.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val summary = obj["summary"]?.jsonPrimitive?.content ?: content
                    val tagsStr = obj["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val source = obj["source"]?.jsonPrimitive?.content ?: "learned"
                    val confidence = obj["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.8f

                    Memory(
                        content = content,
                        summary = summary,
                        tags = tagsStr.toSet(),
                        source = source,
                        confidence = confidence,
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val DISTILLATION_SYSTEM_PROMPT = """你是一个记忆蒸馏器。分析以下对话，提取值得长期记忆的关键信息。

提取规则：
1. 用户明确陈述的事实、偏好、习惯
2. 重要的决策和结论
3. 关键的项目信息（技术栈、配置、约定）
4. 反复出现的话题或关注点

不提取：
1. 一次性问答（天气、时间、简单计算）
2. 闲聊内容
3. 已经过时的信息

输出格式（JSON 数组）：
[
  {
    "content": "提取的关键信息",
    "summary": "简短摘要（用于检索）",
    "tags": ["tag1", "tag2"],
    "source": "user_stated|learned",
    "confidence": 0.0-1.0
  }
]

如果没有值得记忆的信息，返回空数组 []。只输出 JSON，不要其他内容。"""
    }
}
