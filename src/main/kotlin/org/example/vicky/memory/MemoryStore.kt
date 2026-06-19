package org.example.vicky.memory

/**
 * 记忆存储接口。屏蔽底层实现（Qdrant / 本地 / 其他）。
 */
interface MemoryStore {
    /** 保存原始记忆。 */
    suspend fun rememberRaw(rawMemory: RawMemory)

    /** 保存蒸馏记忆。 */
    suspend fun remember(memory: Memory)

    /** 检索蒸馏记忆。 */
    suspend fun recall(query: String, userId: String?, topK: Int): List<Memory>

    /** 检索原始记忆。 */
    suspend fun recallRaw(query: String, userId: String?, topK: Int): List<RawMemory>

    /** 删除蒸馏记忆。 */
    suspend fun forget(id: String)

    /** 删除原始记忆。 */
    suspend fun forgetRaw(id: String)

    /** 删除用户所有蒸馏记忆。 */
    suspend fun forgetByUser(userId: String)

    /** 获取未蒸馏的原始记忆。 */
    suspend fun getUndistilledRawMemories(userId: String? = null): List<RawMemory>

    /** 标记为已蒸馏。 */
    suspend fun markAsDistilled(ids: List<String>)

    /** 清理过期记忆。 */
    suspend fun cleanup()

    /** 获取用户蒸馏记忆数。 */
    suspend fun getUserMemoryCount(userId: String): Int
}
