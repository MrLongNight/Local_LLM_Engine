package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InferenceRequestParcelCompatibilityTest {
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
            assertEquals("legacy request", restored.messages.single().content)
        } finally {
            parcel.recycle()
        }
    }
}
