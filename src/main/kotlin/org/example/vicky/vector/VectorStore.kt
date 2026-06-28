package org.example.vicky.vector

/**
 * 向量记录。
 */
data class VectorRecord(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, Any>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * 向量搜索结果。
 */
data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any>,
)

/**
 * 通用向量存储接口。屏蔽底层实现（Qdrant / 本地 / 其他）。
 */
interface VectorStore {
    /** 批量写入向量记录。 */
    suspend fun upsert(collection: String, records: List<VectorRecord>)

    /** 向量相似度搜索。 */
    suspend fun search(
        collection: String,
        vector: FloatArray,
        topK: Int,
        filter: Map<String, Any>? = null,
    ): List<SearchResult>

    /** 按 ID 批量删除。 */
    suspend fun delete(collection: String, ids: List<String>)

    /** 按条件删除。 */
    suspend fun deleteByFilter(collection: String, filter: Map<String, Any>)

    /** 浏览 collection 中的记录。 */
    suspend fun scroll(
        collection: String,
        limit: Int,
        filter: Map<String, Any>? = null,
    ): List<VectorRecord>

    /** 仅加载 payload，不返回向量数据。用于只需要元数据的场景。 */
    suspend fun scrollPayloadOnly(
        collection: String,
        limit: Int,
        filter: Map<String, Any>? = null,
    ): List<VectorRecord> = scroll(collection, limit, filter)

    /** 确保 collection 存在，不存在则创建。 */
    suspend fun ensureCollection(collection: String, dimension: Int)

    /** 按 ID 更新记录的 payload 字段（不影响向量）。 */
    suspend fun setPayload(collection: String, ids: List<String>, payload: Map<String, Any>)

    /** 关闭存储，释放资源。 */
    fun close() {}
}
