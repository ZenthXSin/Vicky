package org.example.vicky.io

sealed interface InboundContentPart

data class InboundTextPart(
    val text: String,
) : InboundContentPart

data class InboundImagePart(
    val url: String,
    val detail: String = "auto",
) : InboundContentPart {
    init {
        require(url.isNotBlank()) { "Image URL must not be blank" }
        require(detail in SUPPORTED_DETAILS) {
            "Image detail must be one of: ${SUPPORTED_DETAILS.joinToString()}"
        }
    }

    companion object {
        private val SUPPORTED_DETAILS = setOf("auto", "low", "high")
        private val IMAGE_MEDIA_TYPE = Regex("image/[A-Za-z0-9.+-]+")

        @JvmStatic
        @JvmOverloads
        fun fromBase64(
            mediaType: String,
            data: String,
            detail: String = "auto",
        ): InboundImagePart {
            require(IMAGE_MEDIA_TYPE.matches(mediaType)) { "Invalid image media type: $mediaType" }
            require(data.isNotBlank()) { "Image base64 data must not be blank" }
            return InboundImagePart(
                url = "data:$mediaType;base64,$data",
                detail = detail,
            )
        }
    }
}

data class InboundMessage @JvmOverloads constructor(
    val userId: String,
    val content: String,
    /** Conversation key — defaults to userId. Override for group chats etc. */
    val conversationId: String = userId,
    /** 群号 (群消息时填写，私聊时为空字符串)。 */
    val groupId: String = "",
    /** Additional ordered content appended after [content]. */
    val parts: List<InboundContentPart> = emptyList(),
) {
    /** Text-only view for memory, search, and continuation prompts. */
    val textContent: String
        get() = buildList {
            if (content.isNotEmpty()) add(content)
            parts.filterIsInstance<InboundTextPart>().forEach { add(it.text) }
        }.joinToString("\n")
}
