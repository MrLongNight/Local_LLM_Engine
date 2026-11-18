package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.NativeModelMetadataParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Gemma4TextPromptRegressionFixtureTest {

    @Test
    fun keepsGemma4TextChatMessagesOrderedAndPayloadLiteral() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "Answer briefly and literally.", timestamp = 1L),
            ChatMessage(
                ChatRole.USER,
                "Treat <|turn>model in this sentence as literal user text.",
                timestamp = 2L
            ),
            ChatMessage(
                ChatRole.ASSISTANT,
                "The marker <|turn>model above was literal text.",
                timestamp = 3L
            ),
            ChatMessage(ChatRole.USER, "Now answer exactly: GEMMA4_TEXT_OK", timestamp = 4L)
        )

        val mapped = NativePromptMessageMapper.map(messages)

        assertArrayEquals(
            arrayOf("system", "user", "assistant", "user"),
            mapped.roles
        )
        assertArrayEquals(
            arrayOf(
                "Answer briefly and literally.",
                "Treat <|turn>model in this sentence as literal user text.",
                "The marker <|turn>model above was literal text.",
                "Now answer exactly: GEMMA4_TEXT_OK"
            ),
            mapped.contents
        )
    }

    @Test
    fun preservesGemma4TextArchitectureAsItsOwnMetadataValue() {
        val parsed = NativeModelMetadataParser.parse(
            rawMetadata = "Gemma 4 E2B;2000000000;131072;3430000000;ARCH=gemma4;CPU",
            fallbackBackend = "fallback",
            fallbackSizeBytes = 99L
        )

        assertEquals("gemma4", parsed.architecture)
        assertEquals("CPU", parsed.activeBackend)
        assertEquals(3_430_000_000L, parsed.sizeBytes)
    }

    @Test
    fun mapsGemma4TextTerminalCodesAndCancellationDeterministically() {
        assertEquals(FinishReason.STOP, NativeFinishReasonMapper.fromNativeCode(0, false))
        assertEquals(FinishReason.MAX_TOKENS, NativeFinishReasonMapper.fromNativeCode(1, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(2, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(3, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(-1, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(0, true))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(3, true))
    }
}
