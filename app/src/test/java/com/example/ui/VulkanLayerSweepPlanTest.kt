package com.example.ui

import com.example.domain.BackendBenchmarkPlan
import com.example.domain.VulkanLayerSweepPlan

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanLayerSweepPlanTest {

    @Test
    fun buildsDistinctFractionsFromNativeReportedLayerCount() {
        assertEquals(listOf(37, 28, 19, 9), VulkanLayerSweepPlan.layerCounts(37))
        assertEquals(listOf(4, 3, 2, 1), VulkanLayerSweepPlan.layerCounts(4))
        assertEquals(listOf(1), VulkanLayerSweepPlan.layerCounts(1))
    }

    @Test
    fun rejectsInvalidLayerCounts() {
        try {
            VulkanLayerSweepPlan.layerCounts(0)
            throw AssertionError("Expected invalid total layer count to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun parsesOnlyNativeConfirmedLayerDenominator() {
        assertEquals(
            37,
            VulkanLayerSweepPlan.totalLayersFromBackend(
                "Vulkan selected (Vulkan0 / Mali-G57; requested layers 37/37)"
            )
        )
        assertNull(VulkanLayerSweepPlan.totalLayersFromBackend("CPU (registered ggml CPU backend)"))
        assertNull(VulkanLayerSweepPlan.totalLayersFromBackend("Vulkan selected without layer metadata"))
    }

    @Test
    fun configurationChangesOnlyBackendAndGpuLayerCountBesidesBenchmarkSampling() {
        val original = EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = 6,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 37,
            batchThreads = 5,
            microBatchSize = 64,
            temperature = 0.7f,
            topP = 0.8f,
            maxPredictTokens = 512
        )

        val result = VulkanLayerSweepPlan.configuration(original, 19)

        assertEquals(BackendPreference.VULKAN, result.backendPreference)
        assertEquals(19, result.gpuLayers)
        assertEquals(original.contextSize, result.contextSize)
        assertEquals(original.batchSize, result.batchSize)
        assertEquals(original.threads, result.threads)
        assertEquals(original.batchThreads, result.batchThreads)
        assertEquals(original.microBatchSize, result.microBatchSize)
        assertEquals(0f, result.temperature, 0f)
        assertEquals(1f, result.topP, 0f)
        assertEquals(BackendBenchmarkPlan.TOTAL_TOKENS, result.maxPredictTokens)
        assertTrue(result.maxPredictTokens > BackendBenchmarkPlan.WARMUP_TOKENS)
    }
}
