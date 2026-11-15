package com.example.ui

import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup

/**
 * Pure validation rules for the run-correlated Q4_K decode-path A/B sweep.
 * AUTO is evidence-driven: non-Mali devices must retain the pinned upstream
 * reason, while Mali's measured override has its own explicit reason.
 */
internal object Q4kDecodePathPolicy {
    const val BASE_PATH = "BASE_TYPE_MMV"
    const val MMVQ_PATH = "MMVQ_INTEGER_DOT"
    const val FALLBACK_REASON = "q8_1_pipeline_unavailable_fallback"

    fun isKnownKernelPath(path: String): Boolean = path == BASE_PATH || path == MMVQ_PATH

    fun expectedKernelPath(
        requestedWorkgroup: VulkanMatvecWorkgroup,
        observedKernelPath: String,
        observedReasons: Set<String>
    ): String = when {
        requestedWorkgroup == VulkanMatvecWorkgroup.Q4K_BASE_DMMV -> BASE_PATH
        requestedWorkgroup == VulkanMatvecWorkgroup.AUTO -> observedKernelPath
        requestedWorkgroup == VulkanMatvecWorkgroup.MMVQ_FORCE &&
            observedReasons == setOf(FALLBACK_REASON) -> observedKernelPath
        else -> MMVQ_PATH
    }

    fun expectedReason(
        requestedWorkgroup: VulkanMatvecWorkgroup,
        selectedKernelPath: String,
        isMaliDevice: Boolean
    ): String? = when {
        requestedWorkgroup == VulkanMatvecWorkgroup.MMVQ_FORCE &&
            selectedKernelPath == MMVQ_PATH -> "explicit_mmvq_override"
        requestedWorkgroup == VulkanMatvecWorkgroup.Q4K_BASE_DMMV -> "explicit_base_override"
        requestedWorkgroup == VulkanMatvecWorkgroup.AUTO &&
            isMaliDevice && selectedKernelPath == MMVQ_PATH -> "intDot_q4k_batch1"
        else -> null
    }

    fun isValidMmvqSelection(
        requestedWorkgroup: VulkanMatvecWorkgroup,
        isMaliDevice: Boolean,
        selectedReason: String,
        intDotAvailable: Boolean,
        q8Eligible: Boolean,
        q8EligibilityReason: String
    ): Boolean {
        if (!intDotAvailable || !q8Eligible || q8EligibilityReason != "eligible") return false
        val expectedReason = when {
            requestedWorkgroup == VulkanMatvecWorkgroup.MMVQ_FORCE -> "explicit_mmvq_override"
            requestedWorkgroup == VulkanMatvecWorkgroup.AUTO && isMaliDevice -> "intDot_q4k_batch1"
            requestedWorkgroup == VulkanMatvecWorkgroup.AUTO -> "upstream"
            else -> return false
        }
        return selectedReason == expectedReason
    }

    fun expectedMaliAutoReason(
        intDotAvailable: Boolean,
        q8Eligible: Boolean
    ): String = when {
        !intDotAvailable -> "mali_auto_base_without_intDot"
        q8Eligible -> "mali_auto_base_measured_faster"
        else -> "mali_auto_base_q8_1_ineligible"
    }
}
