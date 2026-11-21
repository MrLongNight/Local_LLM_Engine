package com.example.ui

import com.example.ui.diagnostics.InferenceConfigSmokePlan
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceConfigSmokePlanTest {
    @Test
    fun d0AcceptsTheLoadedModelWithoutCheckingItsNameArchitectureSizeOrQuantization() {
        val result = InferenceConfigSmokePlan.assessD0ModelSource(
            ModelInfo(
                name = "Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                path = "/models/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                sizeBytes = 1_234_567_890L,
                parameterCount = 1_234_000_000L,
                architecture = "llama",
                description = "Q5_K_M",
                sha256 = "a".repeat(64)
            )
        )
        assertEquals("SOURCE_SIZE_AND_SHA_AVAILABLE", result.status)
        assertEquals("AVAILABLE", result.shaStatus)
        assertTrue(result.baselineEligible)
    }

    @Test
    fun d0AcceptsDirectSafModelWithoutShaWhenSourceAndSizeAreAvailable() {
        val directSafModel = ModelInfo(
            name = "04.0B_Qwen3-2507_Instruct_256k_Q4_K_M.gguf",
            path = "content://provider.example/models/qwen3.gguf",
            sizeBytes = 2_491_323_904L,
            parameterCount = 4_022_468_096L,
            architecture = "qwen3",
            description = "Qwen3 4B Instruct 2507 | Q4_K - Medium",
            sha256 = null
        )

        val assessment = InferenceConfigSmokePlan.assessD0ModelSource(directSafModel)

        assertEquals("SOURCE_AND_SIZE_ONLY", assessment.status)
        assertEquals("UNAVAILABLE", assessment.shaStatus)
        assertTrue("Direct SAF source with a known size must be eligible without SHA", assessment.baselineEligible)
    }

    @Test
    fun d0OnlyRequiresAModelSourceAndPositiveSizeNotASha() {
        val sourceWithoutSha = ModelInfo(
            name = "Any-Loaded-Model.gguf",
            path = "/models/Any-Loaded-Model.gguf",
            sizeBytes = 2_500_000_000L,
            sha256 = null
        )
        assertTrue(InferenceConfigSmokePlan.assessD0ModelSource(sourceWithoutSha).baselineEligible)

        val emptySource = InferenceConfigSmokePlan.assessD0ModelSource(sourceWithoutSha.copy(path = ""))
        assertFalse(emptySource.baselineEligible)
        assertEquals("MODEL_SOURCE_UNAVAILABLE", emptySource.status)

        val unknownSize = InferenceConfigSmokePlan.assessD0ModelSource(sourceWithoutSha.copy(sizeBytes = 0L))
        assertFalse(unknownSize.baselineEligible)
        assertEquals("MODEL_SOURCE_UNAVAILABLE", unknownSize.status)
    }

    @Test
    fun createsThreeCpuOnlyD0ProfilesWithoutAnyVulkanProfile() {
        val base = EngineConfig(
            contextSize = 2048,
            batchSize = 512,
            threads = 4,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 12,
            temperature = 0.7f,
            topP = 0.8f,
            maxPredictTokens = 512,
            topK = 40,
            minP = 0.1f
        )
        val profiles = InferenceConfigSmokePlan.createD0CpuReferenceProfiles(base, recommendedThreads = 4)

        assertEquals(3, profiles.size)
        assertEquals(
            listOf("cpu-reference-1", "cpu-reference-2", "cpu-reference-3"),
            profiles.map { it.id }
        )
        assertTrue(profiles.all { it.expectedBackend == "CPU" })
        assertTrue(profiles.all { it.config.backendPreference == BackendPreference.CPU })
        assertTrue(profiles.all { it.config.gpuLayers == 0 })
        assertTrue(profiles.all { it.config.contextSize == 256 })
        assertTrue(profiles.all { it.config.batchSize == 128 })
        assertTrue(profiles.all { it.config.microBatchSize == 64 })
        assertTrue(profiles.all { it.config.maxPredictTokens == 96 })
        assertTrue(profiles.all { it.config.temperature == 0f })
        assertTrue(profiles.all { it.config.threads == 6 && it.config.effectiveBatchThreads == 6 })
        assertEquals(profiles[0].config, profiles[1].config)
        assertEquals(profiles[0].config, profiles[2].config)
        assertEquals(
            "D0 CPU profile must use the exact same EngineConfig as the paired runner's CPU profile",
            InferenceConfigSmokePlan.create(base, recommendedThreads = 4).first().config,
            profiles.first().config
        )
        assertEquals(BackendPreference.AUTO, base.backendPreference)
        assertEquals(12, base.gpuLayers)
        assertEquals(2048, base.contextSize)
    }

    @Test
    fun d0ReloadUsesTheSameSizeAndOptionalShaRuleAsThePairedRunner() {
        val directSafSource = ModelInfo(
            name = "Qwen3.gguf",
            path = "content://provider.example/models/qwen3.gguf",
            sizeBytes = 2_491_323_904L,
            sha256 = null
        )
        assertTrue(
            InferenceConfigSmokePlan.matchesReloadedModelIdentity(
                directSafSource,
                directSafSource.copy()
            )
        )
        assertFalse(
            InferenceConfigSmokePlan.matchesReloadedModelIdentity(
                directSafSource,
                directSafSource.copy(sizeBytes = directSafSource.sizeBytes + 1L)
            )
        )

        val hashedSource = directSafSource.copy(sha256 = "a".repeat(64))
        assertTrue(
            InferenceConfigSmokePlan.matchesReloadedModelIdentity(
                hashedSource,
                hashedSource.copy()
            )
        )
        assertFalse(
            InferenceConfigSmokePlan.matchesReloadedModelIdentity(
                hashedSource,
                hashedSource.copy(sha256 = "b".repeat(64))
            )
        )
        assertFalse(
            InferenceConfigSmokePlan.matchesReloadedModelIdentity(
                hashedSource,
                hashedSource.copy(sha256 = null)
            )
        )
    }

    @Test
    fun cpuQ4kReferenceDoesNotRequireVulkanQ4kDecodeEvidence() {
        assertFalse(InferenceConfigSmokePlan.requiresQ4kDecodeEvidence("CPU", true))
        assertFalse(InferenceConfigSmokePlan.requiresQ4kDecodeEvidence("CPU", false))
        assertTrue(InferenceConfigSmokePlan.requiresQ4kDecodeEvidence("VULKAN", true))
        assertFalse(InferenceConfigSmokePlan.requiresQ4kDecodeEvidence("VULKAN", false))
    }

    @Test
    fun createsPairedCpuAndVulkanProfilesWithFixedSharedParameters() {
        val base = EngineConfig(
            contextSize = 2048,
            batchSize = 512,
            threads = 4,
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
            recommendedThreads = 4
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

        assertEquals(256, cpu.config.contextSize)
        assertEquals(cpu.config.contextSize, vulkan.config.contextSize)
        assertEquals(128, cpu.config.batchSize)
        assertEquals(cpu.config.batchSize, vulkan.config.batchSize)
        assertEquals(64, cpu.config.microBatchSize)
        assertEquals(cpu.config.microBatchSize, vulkan.config.microBatchSize)
        assertEquals(64, InferenceConfigSmokePlan.resolvedUbatchForTest(cpu.config, "CPU backend"))
        assertEquals(64, InferenceConfigSmokePlan.resolvedUbatchForTest(vulkan.config, "Vulkan selected"))
        assertEquals(4, cpu.config.threads)
        assertEquals(cpu.config.threads, vulkan.config.threads)
        assertEquals(4, cpu.config.effectiveBatchThreads)
        assertEquals(cpu.config.effectiveBatchThreads, vulkan.config.effectiveBatchThreads)
        assertEquals(20, cpu.config.topK)
        assertEquals(cpu.config.topK, vulkan.config.topK)
        assertEquals(0.05f, cpu.config.minP, 0f)
        assertEquals(cpu.config.minP, vulkan.config.minP, 0f)
        assertEquals(0f, cpu.config.temperature, 0f)
        assertEquals(cpu.config.temperature, vulkan.config.temperature, 0f)
        assertEquals(1f, cpu.config.topP, 0f)
        assertEquals(cpu.config.topP, vulkan.config.topP, 0f)
        assertEquals(VulkanMatvecWorkgroup.AUTO, cpu.config.vulkanMatvecWorkgroup)
        assertEquals(VulkanMatvecWorkgroup.AUTO, vulkan.config.vulkanMatvecWorkgroup)
        assertEquals(FlashAttentionMode.AUTO, cpu.config.flashAttentionMode)
        assertEquals(FlashAttentionMode.AUTO, vulkan.config.flashAttentionMode)
        assertEquals(GdnPolicy.ARCH_DEFAULT, cpu.config.gdnPolicy)
        assertEquals(GdnPolicy.ARCH_DEFAULT, vulkan.config.gdnPolicy)
        assertEquals(false, cpu.config.memoryGuardOverride)
        assertEquals(false, vulkan.config.memoryGuardOverride)
        assertEquals(96, InferenceConfigSmokePlan.MEASURED_TOKENS)
        assertEquals(InferenceConfigSmokePlan.MEASURED_TOKENS, cpu.config.maxPredictTokens)
        assertEquals(cpu.config.maxPredictTokens, vulkan.config.maxPredictTokens)
        // The user's prior settings are not mutated by construction of the smoke profiles.
        assertEquals(2048, base.contextSize)
        assertEquals(512, base.batchSize)
        assertEquals(4, base.threads)
    }

    @Test
    fun smokeProfilesUseTheEngineRecommendedThreadsForBothBackends() {
        val base = EngineConfig(threads = 2)
        val d0Profiles = InferenceConfigSmokePlan.createD0CpuReferenceProfiles(
            base,
            recommendedThreads = 4
        )
        val pairedProfiles = InferenceConfigSmokePlan.create(
            base,
            recommendedThreads = 4
        )

        assertTrue(d0Profiles.all { it.config.threads == 4 })
        assertTrue(d0Profiles.all { it.config.effectiveBatchThreads == 4 })
        assertEquals(4, pairedProfiles[0].config.threads)
        assertEquals(4, pairedProfiles[1].config.threads)
        assertEquals(4, pairedProfiles[0].config.effectiveBatchThreads)
        assertEquals(4, pairedProfiles[1].config.effectiveBatchThreads)
        assertEquals(d0Profiles.first().config, pairedProfiles.first().config)

        val twoThreadRecommendation = InferenceConfigSmokePlan.create(base, recommendedThreads = 2)
        assertEquals(2, twoThreadRecommendation[0].config.threads)
        assertEquals(2, twoThreadRecommendation[1].config.threads)
    }

    @Test
    fun fixedPromptAndExpectedOutputAreStableAndNormalizeOnlyOuterWhitespace() {
        assertEquals(
            "ERGEBNIS=155\nFOLGE=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32",
            InferenceConfigSmokePlan.EXPECTED_OUTPUT
        )
        assertEquals(
            InferenceConfigSmokePlan.EXPECTED_OUTPUT,
            InferenceConfigSmokePlan.normalizeOutput("\r\n" + InferenceConfigSmokePlan.EXPECTED_OUTPUT + "\r\n")
        )
        assertNotEquals(
            InferenceConfigSmokePlan.EXPECTED_OUTPUT,
            InferenceConfigSmokePlan.normalizeOutput("ERGEBNIS=155\nFOLGE=1, 2,3,4")
        )
        assertEquals(true, InferenceConfigSmokePlan.USER_PROMPT.contains("8 * 24 - 37"))
    }

    @Test
    fun evaluatesNumericParityEvenWhenAnOutputGateFails() {
        var parityEvaluationCount = 0
        val summary = InferenceConfigSmokePlan.evaluatePairedGate(
            cpuOutputGatePassed = false,
            vulkanOutputGatePassed = true
        ) {
            parityEvaluationCount++
            "PASS"
        }

        assertEquals(1, parityEvaluationCount)
        assertEquals("FAIL", summary.outputGateStatus)
        assertEquals("PASS", summary.backendParityStatus)
        assertEquals("FAIL", summary.combinedStatus)
    }

    @Test
    fun missingBackendRunKeepsNumericParityUnavailableAndDoesNotInvokeEvaluator() {
        var parityEvaluated = false
        val summary = InferenceConfigSmokePlan.evaluatePairedGate(
            cpuOutputGatePassed = false,
            vulkanOutputGatePassed = null
        ) {
            parityEvaluated = true
            "PASS"
        }

        assertFalse(parityEvaluated)
        assertEquals("FAIL", summary.outputGateStatus)
        assertEquals("UNAVAILABLE", summary.backendParityStatus)
        assertEquals("FAIL", summary.combinedStatus)
    }

    @Test
    fun bothPassingOutputGatesStillRequireAvailablePassingNumericParity() {
        val unavailable = InferenceConfigSmokePlan.evaluatePairedGate(true, true) { "UNAVAILABLE" }
        val passing = InferenceConfigSmokePlan.evaluatePairedGate(true, true) { "PASS" }
        val failing = InferenceConfigSmokePlan.evaluatePairedGate(true, true) { "FAIL" }

        assertEquals("PASS", unavailable.outputGateStatus)
        assertEquals("UNAVAILABLE", unavailable.combinedStatus)
        assertEquals("PASS", passing.combinedStatus)
        assertEquals("FAIL", failing.combinedStatus)
    }

    @Test
    fun continuesAfterProfileFailureOnlyWhenServiceRecoveryIsConfirmed() {
        assertTrue(InferenceConfigSmokePlan.mayContinueAfterProfileFailure(stableStateConfirmed = true))
        assertFalse(InferenceConfigSmokePlan.mayContinueAfterProfileFailure(stableStateConfirmed = false))
    }

    @Test
    fun cancellationSafetyStopAndModelIdentityMismatchAlwaysStopFollowingProfiles() {
        assertFalse(
            InferenceConfigSmokePlan.mayContinueAfterProfileFailure(
                stableStateConfirmed = true,
                cancelled = true
            )
        )
        assertFalse(
            InferenceConfigSmokePlan.mayContinueAfterProfileFailure(
                stableStateConfirmed = true,
                safetyStop = true
            )
        )
        assertFalse(
            InferenceConfigSmokePlan.mayContinueAfterProfileFailure(
                stableStateConfirmed = true,
                modelIdentityMismatch = true
            )
        )
    }

    @Test
    fun resolvesAutoMicroBatchByActualBackendWithoutChangingProfile() {
        val config = EngineConfig(batchSize = 128, backendPreference = BackendPreference.VULKAN)
        assertEquals(64, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "Vulkan selected"))
        assertEquals(128, InferenceConfigSmokePlan.resolvedUbatchForTest(config, "CPU backend"))
    }
}
