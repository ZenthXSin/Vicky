package org.example.vicky.agent

import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object InlineToolCallParser {
    fun parse(content: String): List<ToolCall> {
        if (!content.contains("DSML")) return emptyList()
        val results = mutableListOf<ToolCall>()
        val invokePattern = Regex(
            """<\|?\|?DSML\|?\|?invoke\s+name="([^"]+)">(.*?)</\|?\|?DSML\|?\|?invoke>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val paramPattern = Regex(
            """<\|?\|?DSML\|?\|?parameter\s+name="([^"]+)"[^>]*>(.*?)</\|?\|?DSML\|?\|?parameter>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for ((idx, match) in invokePattern.findAll(content).withIndex()) {
            val toolName = match.groupValues[1]
            val body = match.groupValues[2]
            val args = mutableMapOf<String, JsonElement>()
            for (paramMatch in paramPattern.findAll(body)) {
                val key = paramMatch.groupValues[1]
                val rawValue = paramMatch.groupValues[2].trim()
                val jsonValue = rawValue.toIntOrNull()?.let { JsonPrimitive(it) }
                    ?: rawValue.toLongOrNull()?.let { JsonPrimitive(it) }
                    ?: rawValue.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(rawValue)
                args[key] = jsonValue
            }
            results += ToolCall.Function(
                id = ToolId("inline_${idx}_$toolName"),
                function = FunctionCall(
                    nameOrNull = toolName,
                    argumentsOrNull = JsonObject(args).toString(),
                ),
            )
        }
        return results
    }
}
