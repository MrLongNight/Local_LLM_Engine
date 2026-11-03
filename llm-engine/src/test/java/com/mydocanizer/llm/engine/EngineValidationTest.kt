package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineValidationTest {

    @Test
    fun engineConfigRejectsValuesBelowSupportedBounds() {
        assertTrue(runCatching { EngineConfig(contextSize = 255) }.isFailure)
        assertTrue(runCatching { EngineConfig(batchSize = 63) }.isFailure)
        assertTrue(runCatching { EngineConfig(threads = 0) }.isFailure)
        assertTrue(runCatching { EngineConfig(maxPredictTokens = 0) }.isFailure)
        assertTrue(runCatching { EngineConfig(topP = -0.01f) }.isFailure)
        assertTrue(runCatching { EngineConfig(temperature = Float.POSITIVE_INFINITY) }.isFailure)
    }

    @Test
    fun engineConfigAcceptsLowerBounds() {
        val config = EngineConfig(
            contextSize = 256,
            batchSize = 64,
            threads = 1,
            temperature = 0f,
            topP = 0f,
            maxPredictTokens = 1
        )
        assertEquals(256, config.contextSize)
        assertEquals(64, config.batchSize)
        assertEquals(1, config.threads)
    }

    @Test
    fun inferenceRequestRejectsEmptyMessagesAndInvalidOverrides() {
        assertTrue(runCatching { InferenceRequest(emptyList()) }.isFailure)
        assertTrue(runCatching { InferenceRequest(listOf(ChatMessage.user("hi")), maxPredictTokens = 0) }.isFailure)
        assertTrue(runCatching { InferenceRequest(listOf(ChatMessage.user("hi")), temperature = -0.1f) }.isFailure)
        assertTrue(runCatching { InferenceRequest(listOf(ChatMessage.user("hi")), temperature = Float.NaN) }.isFailure)
        assertTrue(runCatching { InferenceRequest(listOf(ChatMessage.user("hi")), topP = -0.01f) }.isFailure)
        assertTrue(runCatching { InferenceRequest(listOf(ChatMessage.user("hi")), topP = 1.01f) }.isFailure)
        assertEquals(0.75f, InferenceRequest(listOf(ChatMessage.user("hi")), topP = 0.75f).topP)
    }

    @Test
    fun backendModeUnknownValueFallsBackToAuto() {
        assertEquals(BackendPreference.AUTO, BackendPreference.fromModeId(-1))
        assertEquals(BackendPreference.AUTO, BackendPreference.fromModeId(99))
        assertEquals(BackendPreference.VULKAN, BackendPreference.fromModeId(1))
        assertEquals(BackendPreference.CPU, BackendPreference.fromModeId(2))
    }

    @Test
    fun nativeFinishReasonMappingCoversEveryTerminalState() {
        assertEquals(FinishReason.STOP, finishReasonFromNative(0, false))
        assertEquals(FinishReason.MAX_TOKENS, finishReasonFromNative(1, false))
        assertEquals(FinishReason.CANCELLED, finishReasonFromNative(2, false))
        assertEquals(FinishReason.ERROR, finishReasonFromNative(3, false))
        assertEquals(FinishReason.ERROR, finishReasonFromNative(-1, false))
        assertEquals(FinishReason.CANCELLED, finishReasonFromNative(0, true))
        assertEquals(FinishReason.CANCELLED, finishReasonFromNative(1, true))
    }
}
