package com.mydocanizer.llm.engine.internal

/**
 * Pure monotonic-clock timeout policy, kept free of Android and coroutine dependencies
 * so stall semantics can be tested on the host JVM.
 */
internal object InferenceTimeoutPolicy {
    fun prefillStallElapsedMs(nowNanos: Long, lastProgressNanos: Long): Long =
        ((nowNanos - lastProgressNanos).coerceAtLeast(0L) / 1_000_000L)

    fun isPrefillStalled(nowNanos: Long, lastProgressNanos: Long, stallTimeoutMs: Long): Boolean {
        require(stallTimeoutMs > 0L)
        return prefillStallElapsedMs(nowNanos, lastProgressNanos) > stallTimeoutMs
    }
}
