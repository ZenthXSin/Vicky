package org.example.vicky.io

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart

fun InboundMessage.toChatMessage(): ChatMessage {
    if (parts.isEmpty()) {
        return ChatMessage(role = ChatRole.User, content = content)
    }

    val messageParts = buildList<ContentPart> {
        if (content.isNotEmpty()) add(TextPart(content))
        parts.forEach { part ->
            when (part) {
                is InboundTextPart -> add(TextPart(part.text))
                is InboundImagePart -> add(ImagePart(part.url, part.detail))
            }
        }
    }
    return ChatMessage(role = ChatRole.User, content = messageParts)
}

/** Returns text without using ChatMessage.content, which rejects list content. */
fun ChatMessage.textContentOrNull(): String? = when (val value = messageContent) {
    null -> null
    is TextContent -> value.content
    is ListContent -> value.content
        .filterIsInstance<TextPart>()
        .joinToString(separator = "") { it.text }
        .takeIf { it.isNotEmpty() }
    else -> null
}

/** Rough context size estimate that also accounts for image input. */
fun ChatMessage.estimatedContentChars(imageChars: Int = 1_024): Int = when (val value = messageContent) {
    null -> 0
    is TextContent -> value.content.length
    is ListContent -> value.content.sumOf { part ->
        when (part) {
            is TextPart -> part.text.length
            is ImagePart -> imageChars
            else -> 0
        }
    }
    else -> 0
}
