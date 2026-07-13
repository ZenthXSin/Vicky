package org.example.vicky.io

import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class InboundMessageTest {
    @Test
    fun `legacy text input keeps string chat content`() {
        val inbound = InboundMessage("user-1", "hello")

        val message = inbound.toChatMessage()

        assertEquals("user-1", inbound.conversationId)
        assertEquals("hello", message.content)
        assertIs<TextContent>(message.messageContent)
        assertNotNull(
            InboundMessage::class.java.getConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            ),
        )
    }

    @Test
    fun `text and images preserve content order and detail`() {
        val inbound = InboundMessage(
            userId = "user-1",
            content = "compare these",
            parts = listOf(
                InboundImagePart("https://example.com/first.png", detail = "low"),
                InboundTextPart("with this one"),
                InboundImagePart("https://example.com/second.png", detail = "high"),
            ),
        )

        val content = assertIs<ListContent>(inbound.toChatMessage().messageContent)

        assertEquals(4, content.content.size)
        assertEquals("compare these", assertIs<TextPart>(content.content[0]).text)
        assertEquals("https://example.com/first.png", assertIs<ImagePart>(content.content[1]).imageUrl.url)
        assertEquals("low", assertIs<ImagePart>(content.content[1]).imageUrl.detail)
        assertEquals("with this one", assertIs<TextPart>(content.content[2]).text)
        assertEquals("https://example.com/second.png", assertIs<ImagePart>(content.content[3]).imageUrl.url)
        assertEquals("high", assertIs<ImagePart>(content.content[3]).imageUrl.detail)
        assertEquals("compare these\nwith this one", inbound.textContent)
    }

    @Test
    fun `image-only input and base64 data URL are supported`() {
        val image = InboundImagePart.fromBase64("image/png", "YWJj")
        val inbound = InboundMessage(userId = "user-1", content = "", parts = listOf(image))

        val content = assertIs<ListContent>(inbound.toChatMessage().messageContent)
        val imagePart = assertIs<ImagePart>(content.content.single())

        assertEquals("data:image/png;base64,YWJj", imagePart.imageUrl.url)
        assertEquals("", inbound.textContent)
    }

    @Test
    fun `invalid image input fails early`() {
        assertFailsWith<IllegalArgumentException> { InboundImagePart("") }
        assertFailsWith<IllegalArgumentException> { InboundImagePart("https://example.com/a.png", "full") }
        assertFailsWith<IllegalArgumentException> { InboundImagePart.fromBase64("text/plain", "YWJj") }
    }
}
