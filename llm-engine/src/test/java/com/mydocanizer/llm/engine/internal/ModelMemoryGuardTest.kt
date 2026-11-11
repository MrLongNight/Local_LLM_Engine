package com.mydocanizer.llm.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMemoryGuardTest {
    private val mib = 1024L * 1024L

    @Test
    fun keepsRequestedContextWhenMemoryIsSufficient() {
        val plan = ModelMemoryGuard.plan(
            modelBytes = 2_000L * mib,
            requestedContextTokens = 2048,
            availableBytes = 6_000L * mib
        )
        assertEquals(2048, plan.effectiveContextTokens)
        assertFalse(plan.contextReduced)
        assertTrue(plan.requiredBytes <= plan.availableBytes)
    }

    @Test
    fun reducesContextBeforeRejectingLoad() {
        val plan = ModelMemoryGuard.plan(
            modelBytes = 2_000L * mib,
            requestedContextTokens = 4096,
            availableBytes = 3_000L * mib
        )
        assertTrue(plan.contextReduced)
        assertEquals(2048, plan.effectiveContextTokens)
        assertTrue(plan.requiredBytes <= plan.availableBytes)
    }

    @Test(expected = InsufficientModelMemoryException::class)
    fun rejectsModelWhenEvenMinimumContextCannotFit() {
        ModelMemoryGuard.plan(
            modelBytes = 4_000L * mib,
            requestedContextTokens = 2048,
            availableBytes = 3_000L * mib
        )
    }

    @Test(expected = InsufficientModelMemoryException::class)
    fun rejectsWhenAndroidReportsLowMemoryEvenIfEstimateFits() {
        ModelMemoryGuard.plan(
            modelBytes = 1_000L * mib,
            requestedContextTokens = 256,
            availableBytes = 4_000L * mib,
            lowMemory = true
        )
    }
}
