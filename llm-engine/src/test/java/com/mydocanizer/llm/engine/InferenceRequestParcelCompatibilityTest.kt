package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InferenceRequestParcelCompatibilityTest {
    @Test
    fun requestParcelRoundTripsGrammarAndThinkingFlag() {
        val request = InferenceRequest(
            messages = listOf(ChatMessage.user("return JSON")),
            maxPredictTokens = 64,
            enableThinking = true,
            grammar = "root ::= \"OK\""
        )
        val parcel = Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = InferenceRequest.CREATOR.createFromParcel(parcel)
            assertEquals(true, restored.enableThinking)
            assertEquals("root ::= \"OK\"", restored.grammar)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun legacyParcelWithThinkingFlagDefaultsGrammarToNull() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeTypedList(listOf(ChatMessage.user("legacy request")))
            parcel.writeInt(128)
            parcel.writeByte(0)
            parcel.writeByte(0)
            parcel.writeParcelable(null, 0)
            parcel.writeInt(1)
            parcel.setDataPosition(0)

            val restored = InferenceRequest.CREATOR.createFromParcel(parcel)
            assertEquals(true, restored.enableThinking)
            assertNull(restored.grammar)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun legacyParcelDefaultsAppendedThinkingFlagToFalse() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeTypedList(listOf(ChatMessage.user("legacy request")))
            parcel.writeInt(128)
            parcel.writeByte(0) // temperature absent
            parcel.writeByte(0) // topP absent
            parcel.writeParcelable(null, 0) // old payload ends before enableThinking
            parcel.setDataPosition(0)

            val restored = InferenceRequest.CREATOR.createFromParcel(parcel)
            assertEquals(false, restored.enableThinking)
            assertNull(restored.grammar)
            assertEquals("legacy request", restored.messages.single().content)
        } finally {
            parcel.recycle()
        }
    }
}
