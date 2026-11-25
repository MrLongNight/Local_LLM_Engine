package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackendInfoParcelCompatibilityTest {
    @Test
    fun legacyParcelDefaultsNewRuntimeFieldsToUnknown() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("llama.cpp")
            parcel.writeString("NEON=1")
            parcel.writeByte(1)
            parcel.writeInt(4)
            parcel.setDataPosition(0)

            val restored = BackendInfo.CREATOR.createFromParcel(parcel)

            assertTrue(restored.isAvailable)
            assertEquals(4, restored.recommendedThreads)
            assertEquals("UNKNOWN", restored.requestedBackend)
            assertEquals("UNKNOWN", restored.activeBackend)
            assertEquals(-1, restored.requestedGpuLayers)
            assertEquals(-1, restored.allocatedGpuLayers)
            assertEquals(-1, restored.totalGpuLayers)
            assertFalse(restored.cpuFallback)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun runtimeBackendAndGpuAllocationFieldsRoundTrip() {
        val parcel = Parcel.obtain()
        try {
            val original = BackendInfo(
                backendName = "llama.cpp",
                systemInfo = "NEON=1",
                isAvailable = true,
                recommendedThreads = 4,
                requestedBackend = "AUTO",
                activeBackend = "CPU (registered ggml CPU backend)",
                requestedGpuLayers = 99,
                allocatedGpuLayers = 0,
                totalGpuLayers = 37,
                cpuFallback = true
            )
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = BackendInfo.CREATOR.createFromParcel(parcel)

            assertEquals("AUTO", restored.requestedBackend)
            assertEquals("CPU (registered ggml CPU backend)", restored.activeBackend)
            assertEquals(99, restored.requestedGpuLayers)
            assertEquals(0, restored.allocatedGpuLayers)
            assertEquals(37, restored.totalGpuLayers)
            assertTrue(restored.cpuFallback)
        } finally {
            parcel.recycle()
        }
    }
}
