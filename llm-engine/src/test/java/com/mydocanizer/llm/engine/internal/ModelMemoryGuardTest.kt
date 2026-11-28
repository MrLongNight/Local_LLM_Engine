package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.BackendPreference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMemoryGuardTest {
    private val mib = 1024L * 1024L

    @Test
    fun keepsRequestedContextWhenTotalMemoryEstimateIsBelowHardLimit() {
        val plan = ModelMemoryGuard.plan(2_000L * mib, 2048, 6_000L * mib, 8_000L * mib)
        assertEquals(2048, plan.effectiveContextTokens)
        assertFalse(plan.contextReduced)
        assertFalse(plan.pressureWarning)
        assertTrue(plan.requiredBytes <= plan.hardLimitBytes)
    }

    @Test
    fun reducesContextForSoftAvailableMemoryPressureWithoutRejecting() {
        val plan = ModelMemoryGuard.plan(2_000L * mib, 4096, 3_000L * mib, 4_000L * mib)
        assertTrue(plan.contextReduced)
        assertEquals(2048, plan.effectiveContextTokens)
        assertTrue(plan.requiredBytes <= plan.availableBytes)
        assertFalse(plan.hardLimitExceeded)
        assertTrue(plan.pressureWarning)
    }

    @Test
    fun qwen3FourBLoadsWhen2381MibModelHas2770MibAvailable() {
        val plan = ModelMemoryGuard.plan(2_381L * mib, 2048, 2_770L * mib, 7_434L * mib)
        assertTrue(plan.contextReduced)
        assertEquals(512, plan.effectiveContextTokens)
        assertTrue(plan.requiredBytes <= plan.availableBytes)
        assertFalse(plan.hardLimitExceeded)
        assertTrue(plan.pressureWarning)
    }

    @Test
    fun rejectsCpuLoadWhenMinimumContextEstimateExceedsAvailableRam() {
        val modelBytes = 2_000L * mib
        try {
            ModelMemoryGuard.plan(
                modelBytes = modelBytes,
                requestedContextTokens = 2048,
                availableBytes = 1_500L * mib,
                totalBytes = 8_192L * mib,
                requireAvailableMemory = true
            )
            throw AssertionError("CPU load must fail fast instead of risking a process kill")
        } catch (expected: InsufficientAvailableModelMemoryException) {
            assertEquals(
                InsufficientAvailableModelMemoryException.REASON_CPU_AVAILABLE_RAM_LIMIT,
                expected.reasonCode
            )
            assertEquals(ModelMemoryGuard.MIN_CONTEXT_TOKENS, expected.effectiveContextTokens)
            assertTrue(expected.requiredBytes > expected.availableBytes)
            assertTrue(expected.message.orEmpty().startsWith(ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER))
            assertTrue(expected.diagnosticDetails().contains("reason=CPU_AVAILABLE_RAM_LIMIT"))
        }
    }

    @Test
    fun explicitOverrideCanProceedDespiteStrictCpuAvailableRamLimit() {
        val plan = ModelMemoryGuard.plan(
            modelBytes = 2_000L * mib,
            requestedContextTokens = 2048,
            availableBytes = 1_500L * mib,
            totalBytes = 8_192L * mib,
            allowHardLimitOverride = true,
            requireAvailableMemory = true
        )
        assertTrue(plan.availableLimitExceeded)
        assertTrue(plan.availableLimitOverrideApplied)
        assertTrue(plan.pressureWarning)
        assertEquals(ModelMemoryGuard.MIN_CONTEXT_TOKENS, plan.effectiveContextTokens)
    }

    @Test
    fun lowMemorySignalAloneNeverBlocksModelLoad() {
        val plan = ModelMemoryGuard.plan(1_000L * mib, 256, 4_000L * mib, 8_000L * mib, lowMemory = true)
        assertEquals(256, plan.effectiveContextTokens)
        assertTrue(plan.pressureWarning)
        assertFalse(plan.hardLimitExceeded)
    }

    @Test
    fun hardFailsOnlyWhenMinimumContextStillExceeds85PercentOfTotalRam() {
        try {
            ModelMemoryGuard.plan(5_000L * mib, 256, 4_000L * mib, 6_000L * mib)
            throw AssertionError("Expected hard total-memory guard to require an override")
        } catch (expected: InsufficientModelMemoryException) {
            assertTrue(expected.message.orEmpty().startsWith(ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER))
        }
    }

    @Test
    fun hardLimitRejectionExposesEffectiveContextAndStableReasonForLargeModel() {
        val modelBytes = 5_000L * mib
        val requestedContext = 4096

        try {
            ModelMemoryGuard.plan(
                modelBytes = modelBytes,
                requestedContextTokens = requestedContext,
                availableBytes = 4_000L * mib,
                totalBytes = 6_000L * mib
            )
            throw AssertionError("Expected large model to be rejected after minimum-context reduction")
        } catch (expected: InsufficientModelMemoryException) {
            assertEquals(InsufficientModelMemoryException.REASON_TOTAL_RAM_HARD_LIMIT, expected.reasonCode)
            assertEquals(modelBytes, expected.modelBytes)
            assertEquals(requestedContext, expected.requestedContextTokens)
            assertEquals(ModelMemoryGuard.MIN_CONTEXT_TOKENS, expected.effectiveContextTokens)
            assertTrue(expected.requiredBytes > expected.hardLimitBytes)

            val details = expected.diagnosticDetails()
            assertTrue(details.contains("reason=TOTAL_RAM_HARD_LIMIT"))
            assertTrue(details.contains("requestedContext=4096"))
            assertTrue(details.contains("effectiveContext=256"))
            assertTrue(details.contains("requiredBytes=${expected.requiredBytes}"))
            assertTrue(details.contains("hardLimitBytes=${expected.hardLimitBytes}"))
        }
    }

    @Test
    fun explicitOverrideAllowsARequestAboveTheHardLimit() {
        val plan = ModelMemoryGuard.plan(
            5_000L * mib, 256, 4_000L * mib, 6_000L * mib, allowHardLimitOverride = true
        )
        assertTrue(plan.hardLimitExceeded)
        assertTrue(plan.overrideApplied)
        assertEquals(256, plan.effectiveContextTokens)
    }

    @Test
    fun zeroAvailableMemoryIsWarningOnlyWhenTotalMemoryEstimateFits() {
        val plan = ModelMemoryGuard.plan(1_000L * mib, 2048, 0L, 8_000L * mib)
        assertTrue(plan.pressureWarning)
        assertFalse(plan.hardLimitExceeded)
    }
    @Test
    fun estimatesKvAndRuntimeReserveWithoutClaimingMeasuredPeakMemory() {
        val modelBytes = 4_096L
        val expectedKvBytes = 128L * 1024L * 256L
        val plan = ModelMemoryGuard.plan(modelBytes, 256, 4_096L * mib, 8_192L * mib)

        assertEquals(expectedKvBytes, plan.estimatedKvBytes)
        assertEquals(modelBytes + expectedKvBytes + 256L * mib, plan.requiredBytes)
        assertEquals(4_096L * mib, plan.availableBytes)
        assertEquals(8_192L * mib, plan.totalBytes)
        assertFalse(plan.pressureWarning)
    }

    @Test
    fun scalesKvEstimateWithModelFileSizeAboveMinimumPerToken() {
        val modelBytes = 2_000L * mib
        val estimatedKvBytesPerToken = modelBytes / 10_000L
        assertTrue(estimatedKvBytesPerToken > 128L * 1024L)

        val plan = ModelMemoryGuard.plan(modelBytes, 1024, 8_192L * mib, 8_192L * mib)

        assertEquals(estimatedKvBytesPerToken * 1024L, plan.estimatedKvBytes)
        assertEquals(modelBytes + plan.estimatedKvBytes + 256L * mib, plan.requiredBytes)
        assertEquals(1024, plan.effectiveContextTokens)
    }

    @Test
    fun reducesToMinimumContextButKeepsAvailableMemoryPressureAsWarning() {
        val modelBytes = 1_024L * mib
        val availableBytes = 1_024L * mib
        val plan = ModelMemoryGuard.plan(modelBytes, 4096, availableBytes, 8_192L * mib)

        assertEquals(ModelMemoryGuard.MIN_CONTEXT_TOKENS, plan.effectiveContextTokens)
        assertEquals(modelBytes + 32L * mib + 256L * mib, plan.requiredBytes)
        assertTrue(plan.requiredBytes > availableBytes)
        assertTrue(plan.contextReduced)
        assertTrue(plan.pressureWarning)
        assertFalse(plan.hardLimitExceeded)
    }

    @Test
    fun acceptsEstimateExactlyAt85PercentHardLimit() {
        val modelBytes = 18L * mib
        val expectedRequiredBytes = 306L * mib
        val plan = ModelMemoryGuard.plan(
            modelBytes, ModelMemoryGuard.MIN_CONTEXT_TOKENS, expectedRequiredBytes, 360L * mib
        )

        assertEquals(expectedRequiredBytes, plan.requiredBytes)
        assertEquals(expectedRequiredBytes, plan.hardLimitBytes)
        assertFalse(plan.hardLimitExceeded)
        assertFalse(plan.pressureWarning)
    }

    @Test
    fun requiresOverrideOneByteAbove85PercentHardLimit() {
        val modelBytes = 18L * mib + 1L
        try {
            ModelMemoryGuard.plan(modelBytes, ModelMemoryGuard.MIN_CONTEXT_TOKENS, Long.MAX_VALUE, 360L * mib)
            throw AssertionError("Expected one byte above the hard limit to require an override")
        } catch (expected: InsufficientModelMemoryException) {
            assertTrue(expected.message.orEmpty().startsWith(ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER))
        }
    }

    @Test
    fun availableMemoryBoundaryIsSoftAtMinimumContext() {
        val modelBytes = 4_096L
        val requiredBytes = modelBytes + 32L * mib + 256L * mib

        val exactFit = ModelMemoryGuard.plan(modelBytes, 256, requiredBytes, 8_192L * mib)
        val oneByteShort = ModelMemoryGuard.plan(modelBytes, 256, requiredBytes - 1L, 8_192L * mib)

        assertFalse(exactFit.pressureWarning)
        assertEquals(256, oneByteShort.effectiveContextTokens)
        assertTrue(oneByteShort.pressureWarning)
        assertFalse(oneByteShort.hardLimitExceeded)
    }

    @Test
    fun rejectsContextBelowMinimumBeforeBuildingAnEstimate() {
        try {
            ModelMemoryGuard.plan(1_024L, ModelMemoryGuard.MIN_CONTEXT_TOKENS - 1, 1_024L * mib, 4_096L * mib)
            throw AssertionError("Expected context below the minimum to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("at least ${ModelMemoryGuard.MIN_CONTEXT_TOKENS}"))
        }
    }

    @Test
    fun rejectsUnknownOrNonPositiveModelFileSize() {
        listOf(0L, -1L).forEach { modelBytes ->
            try {
                ModelMemoryGuard.plan(modelBytes, 256, 1_024L * mib, 4_096L * mib)
                throw AssertionError("Expected modelBytes=$modelBytes to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Model file size"))
            }
        }
    }


    @Test
    fun requiresSettlingOnlyForUnforcedVulkanToCpuTransitions() {
        assertTrue(
            ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                previousBackend = "Vulkan selected (Mali-G57)",
                requestedBackend = BackendPreference.CPU,
                allowHardLimitOverride = false
            )
        )
        assertFalse(
            ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                previousBackend = "CPU (registered ggml CPU backend)",
                requestedBackend = BackendPreference.CPU,
                allowHardLimitOverride = false
            )
        )
        assertFalse(
            ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                previousBackend = "Vulkan selected (Mali-G57)",
                requestedBackend = BackendPreference.VULKAN,
                allowHardLimitOverride = false
            )
        )
        assertFalse(
            ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                previousBackend = "Vulkan selected (Mali-G57)",
                requestedBackend = BackendPreference.CPU,
                allowHardLimitOverride = true
            )
        )
        assertFalse(
            ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                previousBackend = null,
                requestedBackend = BackendPreference.CPU,
                allowHardLimitOverride = false
            )
        )
    }

    @Test
    fun availableMemoryIsStableOnlyAfterThreeConsecutiveSamplesWithinTolerance() {
        val mib = 1024L * 1024L
        assertFalse(ModelMemoryGuard.hasStableAvailableMemory(listOf(1_000L * mib, 1_020L * mib)))
        assertTrue(
            ModelMemoryGuard.hasStableAvailableMemory(
                listOf(1_000L * mib, 1_060L * mib, 1_128L * mib)
            )
        )
        assertFalse(
            ModelMemoryGuard.hasStableAvailableMemory(
                listOf(1_000L * mib, 1_060L * mib, 1_129L * mib)
            )
        )
    }

}
