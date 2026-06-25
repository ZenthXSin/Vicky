package org.example.vicky.buffer

import java.util.concurrent.ConcurrentHashMap

/**
 * 缓冲区内单条消息的三份存储。
 *
 * @property text 纯文本 (PlainText 提取，可能为空)。
 * @property richMedia 富媒体列表 (图片/音频/视频/闪照等)。
 * @property raw 原始 MessageChain.toString()。
 * @property timestamp 消息到达时间 (epoch ms)。
 * @property userId 发送者 id。
 * @property senderName 发送者显示名 (群名片或昵称)。
 * @property msgRef 消息引用编号 (可选)，用于 reply_message 工具引用。
 */
data class BufferedMessage(
    val text: String,
    val richMedia: List<RichMediaItem>,
    val raw: String,
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String,
    val senderName: String,
    val msgRef: String = "",
)

/**
 * 单条富媒体项。
 *
 * @property type 类型标识: image / audio / video / flashImage / shortVideo 等。
 * @property description 人可读描述 (如图片 url、音频文件名)，供 Agent 理解。
 * @property url 可下载地址 (如果有)。
 * @property raw 原始 MessageElement.toString()，供 Agent 做高级处理。
 */
data class RichMediaItem(
    val type: String,
    val description: String,
    val url: String = "",
    val raw: String = "",
)

/**
 * 按 conversationId 分组的内存消息缓冲区。
 *
 * - TTL 过期自动清理 (查询时惰性触发)。
 * - 单会话超过 [maxEntries] 条时丢弃最早的。
 * - 全局超过 [maxGlobalEntries] 条时按 FIFO 淘汰最旧会话。
 * - 支持 unread 游标：每次查询 unread 后自动推进。
 *
 * @property ttlMs 消息存活时间，默认 1 小时。
 * @property maxEntries 单会话最大消息条数，默认 500。
 * @property maxGlobalEntries 全局最大消息条数，默认 10000。
 * @property rawTruncate raw 字段截断长度，默认 500。
 */
class MessageBuffer(
    private val ttlMs: Long = 3_600_000L,
    private val maxEntries: Int = 500,
    private val maxGlobalEntries: Int = 10000,
    private val rawTruncate: Int = 500,
) {
    private val buffers = ConcurrentHashMap<String, MutableList<BufferedMessage>>()
    private val cursors = ConcurrentHashMap<String, Long>()

    /** 存入一条消息。 */
    fun store(conversationId: String, message: BufferedMessage) {
        val truncatedRaw = if (rawTruncate > 0 && message.raw.length > rawTruncate) {
            message.raw.take(rawTruncate)
        } else {
            message.raw
        }
        val truncated = if (truncatedRaw !== message.raw) message.copy(raw = truncatedRaw) else message

        val list = buffers.getOrPut(conversationId) { mutableListOf() }
        synchronized(list) {
            // 存入时清理过期消息
            val cutoff = System.currentTimeMillis() - ttlMs
            list.removeAll { it.timestamp < cutoff }
            list.add(truncated)
            // 超过上限，丢弃最早的
            while (list.size > maxEntries) list.removeAt(0)
        }
        // 全局清理
        evictIfNeeded()
    }

    /** 获取未过期的消息列表 (游标过滤)。 */
    private fun getValid(conversationId: String, since: Long = 0L): List<BufferedMessage> {
        val list = buffers[conversationId] ?: return emptyList()
        synchronized(list) {
            return list.filter { it.timestamp > since }
        }
    }

    /** 获取纯文本列表。 */
    fun getText(conversationId: String, unread: Boolean = false): List<BufferedMessage> {
        val since = if (unread) cursors[conversationId] ?: 0L else 0L
        return getValid(conversationId, since).filter { it.text.isNotEmpty() }
    }

    /** 获取富媒体列表。 */
    fun getRichMedia(conversationId: String, unread: Boolean = false): List<BufferedMessage> {
        val since = if (unread) cursors[conversationId] ?: 0L else 0L
        return getValid(conversationId, since).filter { it.richMedia.isNotEmpty() }
    }

    /** 获取原始消息列表。 */
    fun getRaw(conversationId: String, unread: Boolean = false): List<BufferedMessage> {
        val since = if (unread) cursors[conversationId] ?: 0L else 0L
        return getValid(conversationId, since)
    }

    /** 推进游标到当前时间，后续 unread 只返回此后的新消息。 */
    fun markRead(conversationId: String) {
        cursors[conversationId] = System.currentTimeMillis()
    }

    /** 清空指定会话的缓冲区。 */
    fun clear(conversationId: String) {
        buffers.remove(conversationId)
        cursors.remove(conversationId)
    }

    /** 指定会话当前缓冲的消息条数。 */
    fun size(conversationId: String): Int =
        buffers[conversationId]?.size ?: 0

    private fun totalEntries(): Int = buffers.values.sumOf { it.size }

    private fun evictIfNeeded() {
        if (totalEntries() <= maxGlobalEntries) return
        // 按最旧消息时间排序，淘汰最旧的会话
        val sorted = buffers.entries.sortedBy { entry ->
            entry.value.minOfOrNull { it.timestamp } ?: 0L
        }
        for (entry in sorted) {
            if (totalEntries() <= maxGlobalEntries) break
            buffers.remove(entry.key)
            cursors.remove(entry.key)
        }
    }
}
