package com.mydocanizer.llm.engine.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceTimeoutPolicyTest {
    @Test
    fun promptProgressRestartsStallDeadlineForLongPrefill() {
        val timeoutMs = 120_000L
        var lastProgress = 0L
        // 1,500 prompt tokens can take much longer than the stall window in total,
        // as long as actual progress keeps arriving before each stall deadline.
        repeat(15) { batch ->
            val now = (batch + 1L) * 60_000_000_000L
            assertFalse(
                "progress batch $batch must restart the prefill stall deadline",
                InferenceTimeoutPolicy.isPrefillStalled(now, lastProgress, timeoutMs)
            )
            lastProgress = now
        }
        val finish = lastProgress + 119_999_000_000L
        assertFalse(InferenceTimeoutPolicy.isPrefillStalled(finish, lastProgress, timeoutMs))
    }

    @Test
    fun prefillTimesOutOnlyAfterNoProgressBeyondStallWindow() {
        val start = 5_000_000_000L
        assertFalse(InferenceTimeoutPolicy.isPrefillStalled(start + 120_000_000_000L, start, 120_000L))
        assertTrue(InferenceTimeoutPolicy.isPrefillStalled(start + 120_001_000_000L, start, 120_000L))
    }

    @Test
    fun elapsedTimeNeverBecomesNegativeWhenClockSampleIsOlder() {
        assertFalse(InferenceTimeoutPolicy.isPrefillStalled(10L, 20L, 1L))
        assertTrue(InferenceTimeoutPolicy.prefillStallElapsedMs(10L, 20L) == 0L)
    }
}
