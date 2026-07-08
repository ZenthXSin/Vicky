package org.example.vicky.vibe.task

import org.example.vicky.vibe.role.AgentRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface TaskGraph {
    fun create(subject: String, role: AgentRole? = null, blockedBy: Set<String> = emptySet()): VibeTask
    fun update(id: String, status: VibeTaskStatus, result: String? = null): VibeTask?
    fun get(id: String): VibeTask?
    fun all(): List<VibeTask>
    fun ready(): List<VibeTask>
    fun clear()
}

class InMemoryTaskGraph : TaskGraph {
    private val tasks = ConcurrentHashMap<String, VibeTask>()
    private val counter = AtomicLong(0)

    override fun create(subject: String, role: AgentRole?, blockedBy: Set<String>): VibeTask {
        val id = "task_${counter.incrementAndGet()}"
        val task = VibeTask(id = id, subject = subject, role = role, blockedBy = blockedBy)
        tasks[id] = task
        return task
    }

    override fun update(id: String, status: VibeTaskStatus, result: String?): VibeTask? {
        return tasks.computeIfPresent(id) { _, old ->
            old.copy(status = status, result = result ?: old.result, updatedAt = System.currentTimeMillis())
        }
    }

    override fun get(id: String): VibeTask? = tasks[id]

    override fun all(): List<VibeTask> = tasks.values.sortedBy { it.createdAt }

    override fun ready(): List<VibeTask> {
        val completedIds = tasks.values.filter { it.status == VibeTaskStatus.COMPLETED }.map { it.id }.toSet()
        return tasks.values.filter { it.status == VibeTaskStatus.PENDING && completedIds.containsAll(it.blockedBy) }
    }

    override fun clear() {
        tasks.clear()
        counter.set(0)
    }
}
