package org.example.vicky.agent

import com.aallam.openai.api.chat.FunctionTool
import com.aallam.openai.api.chat.ToolType
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.vicky.tool.ToolRegistry
import com.aallam.openai.api.chat.Tool as OAITool

object OpenAiToolSchemaBuilder {
    fun build(tools: ToolRegistry): List<OAITool> = tools.snapshot().map { tool ->
        OAITool(
            type = ToolType.Function,
            function = FunctionTool(
                name = tool.name,
                description = tool.description,
                parameters = Parameters.fromJsonString(Json.encodeToString(JsonObject.serializer(), tool.parameters)),
            ),
        )
    }
}
