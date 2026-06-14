package org.example.vicky.io

/** 消息出口 — 由外部提供 (QQ Bot / HTTP / Console / ...)。 */
fun interface MessageSink {
    suspend fun emit(message: OutboundMessage)
}
