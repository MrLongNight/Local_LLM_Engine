package com.example.ui

import com.example.ui.diagnostics.Q4kDecodePathPolicy
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Q4kDecodePathPolicyTest {
    @Test
    fun onlyKnownNativeKernelPathsAreAccepted() {
        assertTrue(Q4kDecodePathPolicy.isKnownKernelPath(Q4kDecodePathPolicy.BASE_PATH))
        assertTrue(Q4kDecodePathPolicy.isKnownKernelPath(Q4kDecodePathPolicy.MMVQ_PATH))
        assertFalse(Q4kDecodePathPolicy.isKnownKernelPath("UNKNOWN_KERNEL_PATH"))
    }

    @Test
    fun nonMaliAutoRetainsUpstreamMmvqSelectionAndReason() {
        assertEquals(
            Q4kDecodePathPolicy.MMVQ_PATH,
            Q4kDecodePathPolicy.expectedKernelPath(
                VulkanMatvecWorkgroup.AUTO,
                Q4kDecodePathPolicy.MMVQ_PATH,
                emptySet()
            )
        )
        assertNull(
            Q4kDecodePathPolicy.expectedReason(
                VulkanMatvecWorkgroup.AUTO,
                Q4kDecodePathPolicy.MMVQ_PATH,
                isMaliDevice = false
            )
        )
        assertTrue(
            Q4kDecodePathPolicy.isValidMmvqSelection(
                VulkanMatvecWorkgroup.AUTO,
                isMaliDevice = false,
                selectedReason = "upstream",
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible"
            )
        )
    }

    @Test
    fun maliAutoMmvqRequiresTheMaliSpecificSelectionReason() {
        assertEquals(
            "intDot_q4k_batch1",
            Q4kDecodePathPolicy.expectedReason(
                VulkanMatvecWorkgroup.AUTO,
                Q4kDecodePathPolicy.MMVQ_PATH,
                isMaliDevice = true
            )
        )
        assertTrue(
            Q4kDecodePathPolicy.isValidMmvqSelection(
                VulkanMatvecWorkgroup.AUTO,
                isMaliDevice = true,
                selectedReason = "intDot_q4k_batch1",
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible"
            )
        )
        assertFalse(
            Q4kDecodePathPolicy.isValidMmvqSelection(
                VulkanMatvecWorkgroup.AUTO,
                isMaliDevice = true,
                selectedReason = "upstream",
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible"
            )
        )
    }

    @Test
    fun explicitBaseAndMmvqOverridesRequireTheirOwnReasons() {
        assertEquals(
            Q4kDecodePathPolicy.BASE_PATH,
            Q4kDecodePathPolicy.expectedKernelPath(
                VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
                Q4kDecodePathPolicy.MMVQ_PATH,
                emptySet()
            )
        )
        assertEquals(
            "explicit_base_override",
            Q4kDecodePathPolicy.expectedReason(
                VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
                Q4kDecodePathPolicy.BASE_PATH,
                isMaliDevice = false
            )
        )
        assertEquals(
            "explicit_mmvq_override",
            Q4kDecodePathPolicy.expectedReason(
                VulkanMatvecWorkgroup.MMVQ_FORCE,
                Q4kDecodePathPolicy.MMVQ_PATH,
                isMaliDevice = false
            )
        )
        assertTrue(
            Q4kDecodePathPolicy.isValidMmvqSelection(
                VulkanMatvecWorkgroup.MMVQ_FORCE,
                isMaliDevice = false,
                selectedReason = "explicit_mmvq_override",
                intDotAvailable = true,
                q8Eligible = true,
                q8EligibilityReason = "eligible"
            )
        )
    }

    @Test
    fun explicitMmvqAllowsOnlyTheExplicitlyReportedPipelineFallback() {
        assertEquals(
            Q4kDecodePathPolicy.BASE_PATH,
            Q4kDecodePathPolicy.expectedKernelPath(
                VulkanMatvecWorkgroup.MMVQ_FORCE,
                Q4kDecodePathPolicy.BASE_PATH,
                setOf(Q4kDecodePathPolicy.FALLBACK_REASON)
            )
        )
        assertEquals(
            Q4kDecodePathPolicy.MMVQ_PATH,
            Q4kDecodePathPolicy.expectedKernelPath(
                VulkanMatvecWorkgroup.MMVQ_FORCE,
                Q4kDecodePathPolicy.BASE_PATH,
                setOf(Q4kDecodePathPolicy.FALLBACK_REASON, "unexpected")
            )
        )
    }

    @Test
    fun maliAutoBaseReasonMatchesHardwareAndEligibility() {
        assertEquals(
            "mali_auto_base_without_intDot",
            Q4kDecodePathPolicy.expectedMaliAutoReason(false, true)
        )
        assertEquals(
            "mali_auto_base_measured_faster",
            Q4kDecodePathPolicy.expectedMaliAutoReason(true, true)
        )
        assertEquals(
            "mali_auto_base_q8_1_ineligible",
            Q4kDecodePathPolicy.expectedMaliAutoReason(true, false)
        )
    }
}
