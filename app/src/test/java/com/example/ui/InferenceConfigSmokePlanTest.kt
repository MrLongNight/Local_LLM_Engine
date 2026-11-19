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
    fun acceptsOnlyTheFixedQwen3FourBInstruct2507Q4KmReferenceForD0() {
        val matching = InferenceConfigSmokePlan.assessD0ReferenceModel(
            ModelInfo(
                name = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                path = "/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                sizeBytes = 2_500_000_000L,
                parameterCount = 4_020_000_000L,
                architecture = "qwen3",
                description = "Q4_K_M",
                sha256 = "a".repeat(64)
            )
        )
        assertEquals("REFERENCE_MODEL_MATCH_SHA_AVAILABLE", matching.status)
        assertTrue(matching.isReferenceModel)
        assertTrue(matching.baselineEligible)
        assertEquals("AVAILABLE", matching.shaStatus)

        val wrongFamily = InferenceConfigSmokePlan.assessD0ReferenceModel(
            ModelInfo(
                name = "Qwen3-1.7B-Q4_K_M.gguf",
                path = "/models/Qwen3-1.7B-Q4_K_M.gguf",
                sizeBytes = 1_700_000_000L,
                parameterCount = 1_700_000_000L,
                architecture = "qwen3",
                description = "Q4_K_M"
            )
        )
        assertEquals("REFERENCE_MODEL_MISMATCH", wrongFamily.status)
        assertFalse(wrongFamily.isReferenceModel)
        assertFalse(wrongFamily.baselineEligible)
        assertTrue(wrongFamily.reason.contains("reference_file_qwen3_4b_instruct_2507"))
    }

    @Test
    fun requiresArchitectureParameterCountAndQ4KmMetadataForD0() {
        val base = ModelInfo(
            name = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            path = "/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            sizeBytes = 2_500_000_000L,
            parameterCount = 4_020_000_000L,
            architecture = "qwen3",
            description = "Q4_K_M",
            sha256 = "b".repeat(64)
        )
        assertFalse(
            InferenceConfigSmokePlan.assessD0ReferenceModel(base.copy(architecture = "qwen2")).isReferenceModel
        )
        assertFalse(
            InferenceConfigSmokePlan.assessD0ReferenceModel(base.copy(parameterCount = 1_700_000_000L)).isReferenceModel
        )
        assertFalse(
            InferenceConfigSmokePlan.assessD0ReferenceModel(base.copy(description = "Q5_K_M")).isReferenceModel
        )
    }

    @Test
    fun missingShaAllowsFunctionalReferenceCheckButDisqualifiesComparableD0Baseline() {
        val result = InferenceConfigSmokePlan.assessD0ReferenceModel(
            ModelInfo(
                name = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                path = "content://provider/document/42",
                sizeBytes = 2_500_000_000L,
                parameterCount = 4_020_000_000L,
                architecture = "qwen3",
                description = "Q4_K_M",
                sha256 = null
            )
        )
        assertEquals("REFERENCE_MODEL_MATCH_SHA_UNAVAILABLE", result.status)
        assertTrue(result.isReferenceModel)
        assertFalse(result.baselineEligible)
        assertEquals("UNAVAILABLE", result.shaStatus)
    }

    @Test
    fun malformedShaDisqualifiesD0ReferenceIdentity() {
        val result = InferenceConfigSmokePlan.assessD0ReferenceModel(
            ModelInfo(
                name = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                path = "/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                sizeBytes = 2_500_000_000L,
                parameterCount = 4_020_000_000L,
                architecture = "qwen3",
                description = "Q4_K_M",
                sha256 = "not-a-sha"
            )
        )
        assertEquals("REFERENCE_MODEL_MISMATCH", result.status)
        assertEquals("INVALID", result.shaStatus)
        assertFalse(result.isReferenceModel)
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
            availableProcessors = 8
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
        assertEquals(6, cpu.config.threads)
        assertEquals(cpu.config.threads, vulkan.config.threads)
        assertEquals(6, cpu.config.effectiveBatchThreads)
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
    fun capsWorkerThreadsToAvailableCpuCores() {
        val profiles = InferenceConfigSmokePlan.create(
            EngineConfig(),
            availableProcessors = 2
        )
        assertEquals(2, profiles[0].config.threads)
        assertEquals(2, profiles[1].config.threads)
        assertEquals(2, profiles[0].config.effectiveBatchThreads)
        assertEquals(2, profiles[1].config.effectiveBatchThreads)
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
