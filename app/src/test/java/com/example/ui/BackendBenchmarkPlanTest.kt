package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendBenchmarkPlanTest {
    @Test
    fun usesShortDeterministicRunAndPreservesLoadParameters() {
        val original = EngineConfig(
            contextSize = 4096,
            batchSize = 256,
            threads = 6,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 37,
            batchThreads = 3,
            microBatchSize = 64,
            maxPredictTokens = 512,
            temperature = 0.7f,
            topP = 0.9f
        )

        val benchmark = BackendBenchmarkPlan.configuration(original)

        assertEquals(24, benchmark.maxPredictTokens)
        assertEquals(0f, benchmark.temperature, 0f)
        assertEquals(1f, benchmark.topP, 0f)
        assertEquals(original.contextSize, benchmark.contextSize)
        assertEquals(original.batchSize, benchmark.batchSize)
        assertEquals(original.threads, benchmark.threads)
        assertEquals(original.backendPreference, benchmark.backendPreference)
        assertEquals(original.gpuLayers, benchmark.gpuLayers)
        assertEquals(original.batchThreads, benchmark.batchThreads)
        assertEquals(original.microBatchSize, benchmark.microBatchSize)
        assertEquals(listOf(BackendPreference.VULKAN, BackendPreference.CPU), BackendBenchmarkPlan.BACKENDS)
    }
}
