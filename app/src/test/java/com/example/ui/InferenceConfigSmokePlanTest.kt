package com.example.ui

import com.example.ui.diagnostics.InferenceConfigSmokePlan
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceConfigSmokePlanTest {
    @Test
    fun createsPairedCpuAndVulkanProfilesWithSharedDeterministicParameters() {
        val base = EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = 6,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 12,
            temperature = 0.7f,
            topP = 0.8f,
            maxPredictTokens = 512,
            topK = 40,
            minP = 0.1f
        )
        val profiles = InferenceConfigSmokePlan.create(
            base,
            availableProcessors = 8,
            activeBackend = "Vulkan selected"
        )

        assertEquals(2, profiles.size)
        val cpu = profiles[0]
        val vulkan = profiles[1]
        assertEquals("cpu-reference", cpu.id)
        assertEquals("CPU", cpu.expectedBackend)
        assertEquals(BackendPreference.CPU, cpu.config.backendPreference)
        assertEquals(0, cpu.config.gpuLayers)
        assertEquals("vulkan-candidate", vulkan.id)
        assertEquals("VULKAN", vulkan.expectedBackend)
        assertEquals(BackendPreference.VULKAN, vulkan.config.backendPreference)
        assertEquals(99, vulkan.config.gpuLayers)

        assertEquals(base.contextSize, cpu.config.contextSize)
        assertEquals(cpu.config.contextSize, vulkan.config.contextSize)
        assertEquals(base.batchSize, cpu.config.batchSize)
        assertEquals(cpu.config.batchSize, vulkan.config.batchSize)
        assertEquals(base.threads, cpu.config.threads)
        assertEquals(cpu.config.threads, vulkan.config.threads)
        assertEquals(base.topK, cpu.config.topK)
        assertEquals(cpu.config.topK, vulkan.config.topK)
        assertEquals(base.minP, cpu.config.minP, 0f)
        assertEquals(cpu.config.minP, vulkan.config.minP, 0f)
        assertEquals(0f, cpu.config.temperature, 0f)
        assertEquals(cpu.config.temperature, vulkan.config.temperature, 0f)
        assertEquals(1f, cpu.config.topP, 0f)
        assertEquals(cpu.config.topP, vulkan.config.topP, 0f)
        assertEquals(InferenceConfigSmokePlan.MEASURED_TOKENS, cpu.config.maxPredictTokens)
        assertEquals(cpu.config.maxPredictTokens, vulkan.config.maxPredictTokens)
    }

    @Test
    fun resolvesAutoMicroBatchByActualBackendWithoutChangingProfile() {
        val config = EngineConfig(batchSize = 128, backendPreference = BackendPreference.VULKAN)
        assertEquals(64, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "Vulkan selected"))
        assertEquals(128, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "CPU backend"))
    }
}
