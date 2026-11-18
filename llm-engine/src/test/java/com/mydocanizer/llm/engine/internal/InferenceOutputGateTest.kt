package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceOutputGateTest {
    @Test
    fun acceptsNonBlankWellFormedOutputOnlyWithStop() {
        val result = InferenceOutputGate.evaluate("A valid answer.\n", 4, FinishReason.STOP)
        assertTrue(result.accepted)
        assertFalse(result.truncated)
        assertNull(result.reason)
    }

    @Test
    fun maxTokensIsExplicitlyMarkedAsTruncated() {
        val result = InferenceOutputGate.evaluate("A partial answer", 12, FinishReason.MAX_TOKENS)
        assertTrue(result.accepted)
        assertTrue(result.truncated)
        assertEquals("MAX_TOKENS", result.reason)
    }

    @Test
    fun rejectsEveryNonCompletionTerminalReason() {
        FinishReason.values()
            .filter { it != FinishReason.STOP && it != FinishReason.MAX_TOKENS }
            .forEach { reason ->
                val result = InferenceOutputGate.evaluate("answer", 2, reason)
                assertFalse("must reject $reason", result.accepted)
                assertFalse("must not classify $reason as truncation", result.truncated)
            }
    }

    @Test
    fun rejectsEmptyBlankAndTokenlessOutputs() {
        assertEquals("EMPTY_OUTPUT", InferenceOutputGate.evaluate(" \n\t", 2, FinishReason.STOP).reason)
        assertEquals("EMPTY_OUTPUT", InferenceOutputGate.evaluate("", 0, FinishReason.STOP).reason)
        assertEquals("NO_OUTPUT_TOKENS", InferenceOutputGate.evaluate("visible", 0, FinishReason.STOP).reason)
    }

    @Test
    fun rejectsUnpairedUtf16Surrogates() {
        val high = String(charArrayOf('\uD800'))
        val low = String(charArrayOf('\uDC00'))
        assertEquals("INVALID_UTF16_SURROGATE", InferenceOutputGate.visibleTextFailure(high))
        assertEquals("INVALID_UTF16_SURROGATE", InferenceOutputGate.visibleTextFailure(low))
    }

    @Test
    fun rejectsUnexpectedC0DelAndC1ControlsButAllowsTextWhitespace() {
        listOf("\u0000", "\u001B", "\u007F", "\u0085").forEach { control ->
            assertEquals("UNEXPECTED_CONTROL_CHARACTER", InferenceOutputGate.visibleTextFailure("before${control}after"))
        }
        assertNull(InferenceOutputGate.visibleTextFailure("first\tsecond\nthird\rfourth"))
    }
}
