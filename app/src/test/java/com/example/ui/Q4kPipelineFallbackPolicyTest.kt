package com.example.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Q4kPipelineFallbackPolicyTest {
    @Test
    fun acceptsExplicitFallbackWhenEligibleQ8PipelineWasUnavailable() {
        assertTrue(
            Q4kPipelineFallbackPolicy.isValid(
                selectedKernelPath = "BASE_TYPE_MMV",
                selectedReason = Q4kPipelineFallbackPolicy.FALLBACK_REASON,
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible",
                quantizeY = "0",
                yKernelType = "f16"
            )
        )
    }

    @Test
    fun rejectsFallbackWhenIntegerDotOrQ8EligibilityIsMissing() {
        assertFalse(isValid(intDotAvailable = false))
        assertFalse(isValid(q8Eligible = false, q8EligibilityReason = "src1_not_f32"))
    }

    @Test
    fun rejectsFallbackWhenNativeEvidenceStillClaimsQ8Execution() {
        assertFalse(isValid(quantizeY = "1"))
        assertFalse(isValid(yKernelType = "q8_1"))
    }

    @Test
    fun rejectsFallbackReasonWhenBaseDidNotActuallyExecute() {
        assertFalse(isValid(selectedKernelPath = "MMVQ_INTEGER_DOT"))
    }

    @Test
    fun leavesNonFallbackDispatchReasonsToTheirOwnValidation() {
        assertTrue(
            Q4kPipelineFallbackPolicy.isValid(
                selectedKernelPath = "MMVQ_INTEGER_DOT",
                selectedReason = "intDot_q4k_batch1",
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible",
                quantizeY = "1",
                yKernelType = "q8_1"
            )
        )
    }

    private fun isValid(
        selectedKernelPath: String = "BASE_TYPE_MMV",
        intDotAvailable: Boolean = true,
        q8Eligible: Boolean = true,
        q8EligibilityReason: String = "eligible",
        quantizeY: String = "0",
        yKernelType: String = "f16"
    ): Boolean = Q4kPipelineFallbackPolicy.isValid(
        selectedKernelPath = selectedKernelPath,
        selectedReason = Q4kPipelineFallbackPolicy.FALLBACK_REASON,
        intDotAvailable = intDotAvailable,
        q8Eligible = q8Eligible,
        q8EligibilityReason = q8EligibilityReason,
        quantizeY = quantizeY,
        yKernelType = yKernelType
    )
}
