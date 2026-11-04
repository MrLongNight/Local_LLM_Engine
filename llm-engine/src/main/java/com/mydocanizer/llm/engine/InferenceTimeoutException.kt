package com.mydocanizer.llm.engine

/**
 * Exception thrown when a tiered timeout threshold is exceeded during engine operations or inference.
 *
 * @property reason The specific tiered timeout that was violated.
 * @property elapsedMs Elapsed duration in milliseconds measured monotonically via [System.nanoTime].
 */
class InferenceTimeoutException(
    val reason: FinishReason,
    val elapsedMs: Long,
    message: String = "Inference timed out ($reason) after ${elapsedMs}ms"
) : RuntimeException(message)
