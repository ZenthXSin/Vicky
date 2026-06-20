package org.example.vicky.memory

import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 蒸馏调度器。定时执行记忆蒸馏任务。
 */
class DistillationScheduler(
    private val memoryStore: MemoryStore,
    private val distiller: Distiller,
    private val maxConversations: Int = 10,
    private val enabled: Boolean = true,
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 启动定时任务。每天凌晨 2:00 执行。
     */
    fun start() {
        if (!enabled) return
        job = scope.launch {
            while (isActive) {
                val now = LocalTime.now()
                val target = LocalTime.of(2, 0)
                val delayMs = if (now.isBefore(target)) {
                    ChronoUnit.MILLIS.between(now, target)
                } else {
                    ChronoUnit.MILLIS.between(now, target) + 24 * 60 * 60 * 1000
                }
                delay(delayMs)
                distillNow()
            }
        }
    }

    /**
     * 停止定时任务。
     */
    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    /**
     * 立即执行一次蒸馏。
     */
    suspend fun distillNow() {
        val undistilled = memoryStore.getUndistilledRawMemories()
        if (undistilled.isEmpty()) return

        val grouped = undistilled.groupBy { Triple(it.userId, it.conversationId, it.turnIndex) }
        val groupsToProcess = grouped.entries.take(maxConversations)

        for ((key, messages) in groupsToProcess) {
            val memories = distiller.distill(messages)
            for (memory in memories) {
                memoryStore.remember(memory)
            }
            val ids = messages.map { it.id }
            memoryStore.markAsDistilled(ids)
        }

        memoryStore.cleanup()
    }

    /**
     * 是否正在运行。
     */
    fun isRunning(): Boolean = job?.isActive == true
}
