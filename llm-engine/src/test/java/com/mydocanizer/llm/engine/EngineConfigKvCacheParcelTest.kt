package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineConfigKvCacheParcelTest {
    @Test
    fun roundTripsIndependentKAndVCacheTypes() {
        val original = EngineConfig(
            kvCacheTypeK = KvCacheType.Q8_0,
            kvCacheTypeV = KvCacheType.F16
        )
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = EngineConfig.CREATOR.createFromParcel(parcel)

            assertEquals(KvCacheType.Q8_0, restored.kvCacheTypeK)
            assertEquals(KvCacheType.F16, restored.kvCacheTypeV)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun legacyParcelDefaultsBothCacheTypesToF16() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(4096)
            parcel.writeInt(512)
            parcel.writeInt(4)
            parcel.writeFloat(0.7f)
            parcel.writeFloat(0.9f)
            parcel.writeInt(1024)
            parcel.writeString(BackendPreference.AUTO.name)
            parcel.writeInt(99)
            parcel.writeParcelable(null, 0)
            parcel.writeInt(0)
            parcel.writeInt(0)
            parcel.writeString(VulkanMatvecWorkgroup.AUTO.name)
            parcel.writeString(FlashAttentionMode.AUTO.name)
            parcel.writeString(ReloadAfterAbort.LAZY.name)
            parcel.writeInt(20)
            parcel.writeFloat(0.05f)
            parcel.writeInt(0)
            parcel.writeString(GdnPolicy.ARCH_DEFAULT.name)
            parcel.setDataPosition(0)

            val restored = EngineConfig.CREATOR.createFromParcel(parcel)

            assertEquals(KvCacheType.F16, restored.kvCacheTypeK)
            assertEquals(KvCacheType.F16, restored.kvCacheTypeV)
        } finally {
            parcel.recycle()
        }
    }
}
