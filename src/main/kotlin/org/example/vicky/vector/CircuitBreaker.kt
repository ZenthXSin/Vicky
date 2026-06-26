package org.example.vicky.vector

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简易断路器：连续失败达阈值后开启，跳过请求；冷却后半开，放行单次探测。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000,
) {
    private val open = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)

    /** 是否允许请求通过。 */
    fun allowRequest(): Boolean {
        if (!open.get()) return true
        // 冷却期过后，放行一次探测
        if (System.currentTimeMillis() - lastFailureTime.get() >= cooldownMs) {
            return true
        }
        return false
    }

    /** 记录成功，重置状态。 */
    fun recordSuccess() {
        failures.set(0)
        open.set(false)
    }

    /** 记录失败，达到阈值则打开断路器。 */
    fun recordFailure() {
        lastFailureTime.set(System.currentTimeMillis())
        if (failures.incrementAndGet() >= failureThreshold) {
            open.set(true)
            println("[Vicky][circuit-breaker] 连续 ${failures.get()} 次失败，断路器开启，${cooldownMs / 1000}s 后重试")
        }
    }

    fun isOpen(): Boolean = open.get()
}
