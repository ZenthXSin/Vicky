package org.example.vicky.session

import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextPart
import kotlinx.serialization.json.Json
import org.example.vicky.io.InboundImagePart
import org.example.vicky.io.InboundMessage
import org.example.vicky.io.InboundTextPart
import org.example.vicky.io.toChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageDtoTest {
    @Test
    fun `multimodal content survives session DTO round trip`() {
        val original = InboundMessage(
            userId = "user-1",
            content = "inspect",
            parts = listOf(
                InboundImagePart("https://example.com/image.png", detail = "high"),
                InboundTextPart("carefully"),
            ),
        ).toChatMessage()

        val restored = original.toDto().toMsg()
        val content = assertIs<ListContent>(restored.messageContent)

        assertEquals("inspect", assertIs<TextPart>(content.content[0]).text)
        val image = assertIs<ImagePart>(content.content[1])
        assertEquals("https://example.com/image.png", image.imageUrl.url)
        assertEquals("high", image.imageUrl.detail)
        assertEquals("carefully", assertIs<TextPart>(content.content[2]).text)
    }

    @Test
    fun `legacy persisted text remains readable`() {
        val dto = Json.decodeFromString<MsgDto>(
            """{"role":"user","content":"legacy text"}""",
        )

        assertEquals("legacy text", dto.toMsg().content)
    }
}
