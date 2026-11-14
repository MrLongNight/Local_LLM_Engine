package com.example.ui

/**
 * Accepts a Q8_1 pipeline fallback only when native evidence proves that the
 * integer-dot/Q8_1 route was eligible but its pipeline was unavailable and
 * BASE actually executed. A fallback reason on any other path is contradictory.
 */
internal object Q4kPipelineFallbackPolicy {
    const val FALLBACK_REASON = "q8_1_pipeline_unavailable_fallback"
    const val BASE_PATH = "BASE_TYPE_MMV"

    fun isValid(
        selectedKernelPath: String,
        selectedReason: String,
        intDotAvailable: Boolean,
        q8Eligible: Boolean,
        q8EligibilityReason: String,
        quantizeY: String,
        yKernelType: String
    ): Boolean {
        if (selectedReason != FALLBACK_REASON) return true
        return selectedKernelPath == BASE_PATH &&
            intDotAvailable &&
            q8Eligible &&
            q8EligibilityReason == "eligible" &&
            quantizeY == "0" &&
            yKernelType != "q8_1"
    }
}
