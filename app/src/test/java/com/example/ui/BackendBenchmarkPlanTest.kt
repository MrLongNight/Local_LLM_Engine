package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.GdnPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendBenchmarkPlanTest {
    @Test
    fun acceptancePassContainsOnlyCpuReferenceAndVulkanCandidateWithMatchingSettings() {
        val original = EngineConfig(
            contextSize = 4096,
            batchSize = 256,
            threads = 6,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 37,
            batchThreads = 3,
            microBatchSize = 64,
            flashAttentionMode = FlashAttentionMode.DISABLED
        )
        val profiles = BackendBenchmarkPlan.backendAcceptanceProfiles(original)

        assertEquals(listOf(BackendPreference.CPU, BackendPreference.VULKAN), profiles.map { it.backend })
        assertEquals(listOf("CPU_REFERENCE", "VULKAN_CANDIDATE"), profiles.map { it.id })
        assertEquals(0, profiles[0].config.gpuLayers)
        assertEquals(99, profiles[1].config.gpuLayers)
        assertEquals(64, profiles[0].microBatchSize)
        assertEquals(64, profiles[1].microBatchSize)

        val cpu = profiles[0].config
        val vulkan = profiles[1].config
        assertEquals(cpu.contextSize, vulkan.contextSize)
        assertEquals(cpu.batchSize, vulkan.batchSize)
        assertEquals(cpu.threads, vulkan.threads)
        assertEquals(cpu.batchThreads, vulkan.batchThreads)
        assertEquals(cpu.microBatchSize, vulkan.microBatchSize)
        assertEquals(cpu.gdnPolicy, vulkan.gdnPolicy)
        assertEquals(cpu.flashAttentionMode, vulkan.flashAttentionMode)
    }

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
    fun fullMatrixCoversWorkgroupsAndQ4KSpecificPathsForMatchingModel() {
        val profiles = BackendBenchmarkPlan.fullMatrixProfiles(
            EngineConfig(batchSize = 128, threads = 6),
            "Qwen3.5-4B-Q4_K_M.gguf"
        )
        assertEquals(42, profiles.size)
        assertEquals(42, profiles.map { it.id }.distinct().size)
        assertEquals(35, profiles.count { it.backend == BackendPreference.VULKAN })
        assertEquals(7, profiles.count { it.backend == BackendPreference.CPU })
        assertEquals(setOf(32, 64, 128), profiles.map { it.microBatchSize }.toSet())
        assertTrue(profiles.any { it.kernelProfile == VulkanMatvecWorkgroup.SUBGROUP })
        assertTrue(profiles.any { it.kernelProfile == VulkanMatvecWorkgroup.LARGE })
        assertTrue(profiles.any {
            it.kernelProfile == VulkanMatvecWorkgroup.Q4K_BASE_DMMV &&
                it.flashAttention == FlashAttentionMode.ENABLED
        })
        assertTrue(profiles.any { it.kernelProfile == VulkanMatvecWorkgroup.Q4K_PREFILL_F16 })
        assertTrue(profiles.none {
            it.kernelProfile == VulkanMatvecWorkgroup.Q5K_BASE_DMMV ||
                it.kernelProfile == VulkanMatvecWorkgroup.Q6K_BASE_DMMV
        })
        assertTrue(profiles.filter { it.backend == BackendPreference.VULKAN }.all {
            it.config.gpuLayers == 99 &&
                it.config.threads <= 2 &&
                it.config.effectiveBatchThreads <= 2
        })
        assertTrue(profiles.filter { it.backend == BackendPreference.CPU }.all {
            it.config.gpuLayers == 0 &&
                it.kernelProfile == VulkanMatvecWorkgroup.AUTO
        })
    }

    @Test
    fun fullMatrixIncludesQuantizationSpecificQ5AndQ6PathsOnlyWhenRelevant() {
        val config = EngineConfig(batchSize = 128)
        val q5Profiles = BackendBenchmarkPlan.fullMatrixProfiles(config, "Qwen3.5-4B-Q5_K_M.gguf")
        val q6Profiles = BackendBenchmarkPlan.fullMatrixProfiles(config, "Qwen3.5-4B-Q6_K.gguf")
        assertEquals(35, q5Profiles.size)
        assertEquals(35, q6Profiles.size)
        assertTrue(q5Profiles.any { it.kernelProfile == VulkanMatvecWorkgroup.Q5K_BASE_DMMV })
        assertTrue(q6Profiles.any { it.kernelProfile == VulkanMatvecWorkgroup.Q6K_BASE_DMMV })
        assertTrue(q5Profiles.none { it.kernelProfile == VulkanMatvecWorkgroup.Q6K_BASE_DMMV })
        assertTrue(q6Profiles.none { it.kernelProfile == VulkanMatvecWorkgroup.Q5K_BASE_DMMV })
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
    @Test
    fun fullMatrixIncludesFlashAttentionAutoAtUbatch64ForCpuAndVulkanReferences() {
        val profiles = BackendBenchmarkPlan.fullMatrixProfiles(
            EngineConfig(batchSize = 128),
            "Qwen3.5-4B-Q4_K_M.gguf"
        )
        val autoAt64 = profiles.filter {
            it.microBatchSize == 64 && it.flashAttention == FlashAttentionMode.AUTO
        }
        assertEquals(6, autoAt64.size)
        assertTrue(autoAt64.any { it.backend == BackendPreference.CPU })
        assertEquals(
            setOf(
                VulkanMatvecWorkgroup.AUTO,
                VulkanMatvecWorkgroup.SUBGROUP,
                VulkanMatvecWorkgroup.LARGE,
                VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
                VulkanMatvecWorkgroup.Q4K_PREFILL_F16
            ),
            autoAt64.filter { it.backend == BackendPreference.VULKAN }
                .map { it.kernelProfile }.toSet()
        )
        assertTrue(autoAt64.filter { it.backend == BackendPreference.CPU }.all {
            it.kernelProfile == VulkanMatvecWorkgroup.AUTO && it.config.gpuLayers == 0
        })
        assertTrue(autoAt64.filter { it.backend == BackendPreference.VULKAN }.all {
            it.config.gpuLayers == 99
        })
    }

    @Test
    fun qwen35RunsExactGdnFusionMatrixWithCpuReferencesBeforeAutoAndForcedMmvq() {
        val profiles = BackendBenchmarkPlan.fullMatrixProfiles(
            EngineConfig(batchSize = 128),
            "Qwen3.5-2B-Q4_K_M.gguf",
            "qwen35"
        )
        assertEquals(48, profiles.size)
        assertEquals(16, profiles.count { it.backend == BackendPreference.CPU })
        assertEquals(32, profiles.count { it.backend == BackendPreference.VULKAN })
        assertEquals(
            setOf(
                GdnPolicy.BOTH_FUSED,
                GdnPolicy.BOTH_UNFUSED,
                GdnPolicy.AR_FUSED_CH_UNFUSED,
                GdnPolicy.AR_UNFUSED_CH_FUSED
            ),
            profiles.map { it.config.gdnPolicy }.toSet()
        )
        assertEquals(setOf(32, 128), profiles.map { it.microBatchSize }.toSet())
        assertEquals(
            setOf(FlashAttentionMode.DISABLED, FlashAttentionMode.ENABLED),
            profiles.map { it.flashAttention }.toSet()
        )
        assertEquals(16, profiles.count {
            it.backend == BackendPreference.VULKAN && it.kernelProfile == VulkanMatvecWorkgroup.AUTO
        })
        assertEquals(16, profiles.count {
            it.backend == BackendPreference.VULKAN && it.kernelProfile == VulkanMatvecWorkgroup.MMVQ_FORCE
        })
        for (policy in listOf(
            GdnPolicy.BOTH_FUSED,
            GdnPolicy.BOTH_UNFUSED,
            GdnPolicy.AR_FUSED_CH_UNFUSED,
            GdnPolicy.AR_UNFUSED_CH_FUSED
        )) {
            val group = profiles.filter { it.config.gdnPolicy == policy }
            assertEquals(12, group.size)
            assertEquals(4, group.takeWhile { it.backend == BackendPreference.CPU }.size)
            assertTrue(group.take(4).all { it.backend == BackendPreference.CPU && it.config.gpuLayers == 0 })
            assertTrue(group.drop(4).all { it.backend == BackendPreference.VULKAN && it.config.gpuLayers == 99 })
        }
    }

    @Test
    fun fullMatrixRunsCpuReferenceProfilesBeforeVulkanCandidates() {
        val profiles = BackendBenchmarkPlan.fullMatrixProfiles(
            EngineConfig(batchSize = 128),
            "Qwen3.5-4B-Q4_K_M.gguf"
        )
        val firstVulkan = profiles.indexOfFirst { it.backend == BackendPreference.VULKAN }
        assertTrue(firstVulkan > 0)
        assertTrue(profiles.take(firstVulkan).all { it.backend == BackendPreference.CPU })
        assertTrue(profiles.drop(firstVulkan).all { it.backend == BackendPreference.VULKAN })
        assertEquals(16, BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS)
        assertTrue(BackendBenchmarkPlan.correctnessGatePrompt().contains("2, 4, 6"))
        assertTrue(BackendBenchmarkPlan.correctnessGatePrompt().contains("318, 320"))
    }

}
