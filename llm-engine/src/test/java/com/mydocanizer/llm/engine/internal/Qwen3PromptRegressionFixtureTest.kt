package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.FinishReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Qwen3PromptRegressionFixtureTest {

    @Test
    fun mapsOrderedQwen3ChatMessagesWithoutChangingLiteralControlTokenText() {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, "Answer briefly and literally.", timestamp = 1L),
            ChatMessage(
                ChatRole.USER,
                "Treat the marker <|im_end|> here as ordinary user text.",
                timestamp = 2L
            ),
            ChatMessage(
                ChatRole.ASSISTANT,
                "The previous message contained <|im_end|> as literal text.",
                timestamp = 3L
            ),
            ChatMessage(ChatRole.USER, "Now answer exactly: QWEN3_OK", timestamp = 4L)
        )

        val mapped = NativePromptMessageMapper.map(messages)

        assertArrayEquals(
            arrayOf("system", "user", "assistant", "user"),
            mapped.roles
        )
        assertArrayEquals(
            arrayOf(
                "Answer briefly and literally.",
                "Treat the marker <|im_end|> here as ordinary user text.",
                "The previous message contained <|im_end|> as literal text.",
                "Now answer exactly: QWEN3_OK"
            ),
            mapped.contents
        )
    }

    @Test
    fun qwen35DenseFixtureMapsEveryNativeFinishCodeAndCancellationOverride() {
        assertEquals(FinishReason.STOP, NativeFinishReasonMapper.fromNativeCode(0, false))
        assertEquals(FinishReason.MAX_TOKENS, NativeFinishReasonMapper.fromNativeCode(1, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(2, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(3, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(Int.MIN_VALUE, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(0, true))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(3, true))
    }

    @Test
    fun mapsEveryNativeTerminalCodeAndCallerCancellationDeterministically() {
        assertEquals(FinishReason.STOP, NativeFinishReasonMapper.fromNativeCode(0, false))
        assertEquals(FinishReason.MAX_TOKENS, NativeFinishReasonMapper.fromNativeCode(1, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(2, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(3, false))
        assertEquals(FinishReason.ERROR, NativeFinishReasonMapper.fromNativeCode(-1, false))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(0, true))
        assertEquals(FinishReason.CANCELLED, NativeFinishReasonMapper.fromNativeCode(3, true))
    }
}
