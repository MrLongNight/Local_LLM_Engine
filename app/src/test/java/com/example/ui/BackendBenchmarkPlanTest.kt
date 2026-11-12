package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import com.mydocanizer.llm.engine.FlashAttentionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendBenchmarkPlanTest {
    @Test
    fun q4kDecodePathSweepChangesOnlyTheInternalQ4kKernelModeAndForcesFullOffload() {
        val original = EngineConfig(
            contextSize = 4096,
            batchSize = 256,
            threads = 6,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 37,
            batchThreads = 3,
            microBatchSize = 64
        )
        val profiles = BackendBenchmarkPlan.vulkanQ4kDecodeConfigurations(original)

        assertEquals(
            listOf(
                VulkanMatvecWorkgroup.AUTO,
                VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
                VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
                VulkanMatvecWorkgroup.AUTO
            ),
            profiles.map { it.vulkanMatvecWorkgroup }
        )
        profiles.forEach { profile ->
            assertEquals(BackendPreference.VULKAN, profile.backendPreference)
            assertEquals(99, profile.gpuLayers)
            assertEquals(24, profile.maxPredictTokens)
            assertEquals(0f, profile.temperature, 0f)
            assertEquals(1f, profile.topP, 0f)
            assertEquals(original.contextSize, profile.contextSize)
            assertEquals(original.batchSize, profile.batchSize)
            assertEquals(original.threads, profile.threads)
            assertEquals(original.batchThreads, profile.batchThreads)
            assertEquals(original.microBatchSize, profile.microBatchSize)
        }
    }
    @Test
    fun q5Q6BasePathsAreAvailableForMeasurementButAutoIsRetained() {
        val profiles = BackendBenchmarkPlan.vulkanQ5Q6DecodeConfigurations(EngineConfig())
        assertEquals(listOf(VulkanMatvecWorkgroup.AUTO, VulkanMatvecWorkgroup.Q5K_BASE_DMMV,
            VulkanMatvecWorkgroup.AUTO, VulkanMatvecWorkgroup.Q6K_BASE_DMMV),
            profiles.map { it.vulkanMatvecWorkgroup })
        assertTrue(profiles.all { it.backendPreference == BackendPreference.VULKAN && it.gpuLayers == 99 })
    }

    @Test
    fun q4kPrefillSweepHasExplicitF16Profiles() {
        val profiles = BackendBenchmarkPlan.vulkanQ4kPrefillConfigurations(EngineConfig())
        assertEquals(listOf(VulkanMatvecWorkgroup.AUTO, VulkanMatvecWorkgroup.Q4K_PREFILL_F16,
            VulkanMatvecWorkgroup.Q4K_PREFILL_F16, VulkanMatvecWorkgroup.AUTO),
            profiles.map { it.vulkanMatvecWorkgroup })
    }

    @Test
    fun microBatchSweepCovers32_64_128() {
        val profiles = BackendBenchmarkPlan.vulkanMicroBatchConfigurations(EngineConfig(batchSize = 128))
        assertEquals(listOf(32, 64, 128), profiles.map { it.microBatchSize })
        assertTrue(profiles.all { it.backendPreference == BackendPreference.VULKAN && it.gpuLayers == 99 })
    }

    @Test
    fun fullMatrixCoversBackendDecodeMicroBatchAndFlashAttentionAxes() {
        val profiles = BackendBenchmarkPlan.fullMatrixProfiles(EngineConfig(batchSize = 128, threads = 6))
        assertEquals(18, profiles.size)
        assertEquals(18, profiles.map { it.id }.distinct().size)
        assertEquals(12, profiles.count { it.backend == BackendPreference.VULKAN })
        assertEquals(6, profiles.count { it.backend == BackendPreference.CPU })
        assertEquals(setOf(32, 64, 128), profiles.map { it.microBatchSize }.toSet())
        assertTrue(profiles.any {
            it.backend == BackendPreference.VULKAN &&
                it.decodePath == VulkanMatvecWorkgroup.Q4K_BASE_DMMV &&
                it.flashAttention == FlashAttentionMode.ENABLED
        })
        assertTrue(profiles.filter { it.backend == BackendPreference.VULKAN }.all {
            it.config.gpuLayers == 99 &&
                it.config.threads <= 2 &&
                it.config.effectiveBatchThreads <= 2
        })
        assertTrue(profiles.filter { it.backend == BackendPreference.CPU }.all {
            it.config.gpuLayers == 0 &&
                it.decodePath == VulkanMatvecWorkgroup.AUTO
        })
    }

    @Test
    fun flashAttentionSweepForcesOnAndOffForBothBackends() {
        val profiles = BackendBenchmarkPlan.flashAttentionConfigurations(EngineConfig())
        assertEquals(8, profiles.size)
        assertEquals(listOf(FlashAttentionMode.DISABLED, FlashAttentionMode.ENABLED,
            FlashAttentionMode.ENABLED, FlashAttentionMode.DISABLED,
            FlashAttentionMode.DISABLED, FlashAttentionMode.ENABLED,
            FlashAttentionMode.ENABLED, FlashAttentionMode.DISABLED),
            profiles.map { it.flashAttentionMode })
        assertEquals(listOf(BackendPreference.VULKAN, BackendPreference.VULKAN,
            BackendPreference.VULKAN, BackendPreference.VULKAN,
            BackendPreference.CPU, BackendPreference.CPU,
            BackendPreference.CPU, BackendPreference.CPU),
            profiles.map { it.backendPreference })
    }

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
        assertEquals(4, BackendBenchmarkPlan.WARMUP_TOKENS)
        assertEquals(20, BackendBenchmarkPlan.MEASURED_TOKENS)
        assertEquals(24, BackendBenchmarkPlan.TOTAL_TOKENS)
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

    @Test
    fun vulkanMatvecSweepChangesOnlyWorkgroupAndForcesFullOffload() {
        val original = EngineConfig(
            contextSize = 4096,
            batchSize = 256,
            threads = 6,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 37,
            batchThreads = 3,
            microBatchSize = 64
        )
        val profiles = BackendBenchmarkPlan.vulkanMatvecConfigurations(original)

        assertEquals(
            listOf(
                VulkanMatvecWorkgroup.AUTO,
                VulkanMatvecWorkgroup.SUBGROUP,
                VulkanMatvecWorkgroup.LARGE,
                VulkanMatvecWorkgroup.AUTO
            ),
            profiles.map { it.vulkanMatvecWorkgroup }
        )
        profiles.forEach { profile ->
            assertEquals(BackendPreference.VULKAN, profile.backendPreference)
            assertEquals(99, profile.gpuLayers)
            assertEquals(24, profile.maxPredictTokens)
            assertEquals(0f, profile.temperature, 0f)
            assertEquals(1f, profile.topP, 0f)
            assertEquals(original.contextSize, profile.contextSize)
            assertEquals(original.batchSize, profile.batchSize)
            assertEquals(original.threads, profile.threads)
            assertEquals(original.batchThreads, profile.batchThreads)
            assertEquals(original.microBatchSize, profile.microBatchSize)
        }
    }
}
