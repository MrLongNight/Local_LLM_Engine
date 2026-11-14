package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InferenceResultParcelCompatibilityTest {
    @Test
    fun legacyParcelableDefaultsGraphMetricsAndBothRunIds() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("legacy answer")
            parcel.writeInt(7)
            parcel.writeLong(1400L)
            parcel.writeDouble(5.0)
            parcel.writeString(FinishReason.STOP.name)
            parcel.setDataPosition(0)

            val restored = InferenceResult.CREATOR.createFromParcel(parcel)
            assertEquals("legacy answer", restored.text)
            assertEquals(7, restored.tokensGenerated)
            assertEquals(-1, restored.graphSplits)
            assertEquals("", restored.diagnosticRunId)
            assertEquals("", restored.nativeRunId)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun intermediateParcelableWithOnlyResourceRunIdDefaultsNativeRunId() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("older answer")
            parcel.writeInt(3)
            parcel.writeLong(900L)
            parcel.writeDouble(3.3)
            parcel.writeString(FinishReason.STOP.name)
            parcel.writeInt(2)
            parcel.writeString("request-older")
            parcel.setDataPosition(0)

            val restored = InferenceResult.CREATOR.createFromParcel(parcel)
            assertEquals(2, restored.graphSplits)
            assertEquals("request-older", restored.diagnosticRunId)
            assertEquals("", restored.nativeRunId)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun appendedGraphSplitsAndBothRunIdsRoundTrip() {
        val parcel = Parcel.obtain()
        try {
            val original = InferenceResult(
                text = "answer",
                tokensGenerated = 24,
                durationMs = 4800L,
                tokensPerSecond = 5.0,
                finishReason = FinishReason.MAX_TOKENS,
                graphSplits = 3,
                diagnosticRunId = "request-123",
                nativeRunId = "native-456"
            )
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = InferenceResult.CREATOR.createFromParcel(parcel)
            assertEquals(3, restored.graphSplits)
            assertEquals("request-123", restored.diagnosticRunId)
            assertEquals("native-456", restored.nativeRunId)
        } finally {
            parcel.recycle()
        }
    }
}
