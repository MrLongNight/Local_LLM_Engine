package com.mydocanizer.llm.engine.internal

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
}
