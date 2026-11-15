package com.example.ui

import com.example.ui.diagnostics.InferenceConfigSmokePlan
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceConfigSmokePlanTest {
    @Test
    fun createsExactlyOneDeterministicFullOffloadVulkanProfile() {
        val base = EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = 6,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            temperature = 0.7f,
            topP = 0.8f,
            maxPredictTokens = 512
        )
        val profiles = InferenceConfigSmokePlan.create(
            base,
            availableProcessors = 8,
            activeBackend = "CPU"
        )
        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertEquals("vulkan-single-run", profile.id)
        assertEquals(BackendPreference.VULKAN, profile.config.backendPreference)
        assertEquals(99, profile.config.gpuLayers)
        assertEquals(2048, profile.config.contextSize)
        assertEquals(128, profile.config.batchSize)
        assertEquals(6, profile.config.threads)
        assertEquals(0f, profile.config.temperature, 0f)
        assertEquals(1f, profile.config.topP, 0f)
        assertEquals(
            InferenceConfigSmokePlan.WARMUP_TOKENS + InferenceConfigSmokePlan.MEASURED_TOKENS,
            profile.config.maxPredictTokens
        )
    }

    @Test
    fun resolvesAutoMicroBatchByBackendWithoutChangingTheProfile() {
        val config = EngineConfig(batchSize = 128, backendPreference = BackendPreference.VULKAN)
        assertEquals(64, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "Vulkan selected"))
        assertEquals(128, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "CPU backend"))
    }
}
