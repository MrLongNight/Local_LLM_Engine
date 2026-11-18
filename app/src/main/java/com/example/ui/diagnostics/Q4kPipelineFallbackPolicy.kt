package com.example.ui.diagnostics

/**
 * Validates run-correlated native evidence for a Q4_K MMVQ fallback.
 *
 * A fallback is accepted only if the Q8_1 path was actually requested, its
 * missing component is identified, and the compatible F32 base DMMV was selected.
 */
internal object Q4kPipelineFallbackPolicy {
    const val FALLBACK_REASON = "q8_1_pipeline_unavailable_fallback"
    const val BASE_PATH = "BASE_TYPE_MMV"
    private const val MMVQ_PATH = "MMVQ_INTEGER_DOT"
    private const val DECODE_MARKER = "LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - "

    enum class Status { NOT_APPLICABLE, VALID, INVALID }

    data class Validation(
        val status: Status,
        val reason: String,
        val fallbackEvents: Int = 0
    )

    fun validateNativeEvidence(lines: List<String>): Validation {
        val events = lines.mapNotNull(::parseDecodeEvent)
        if (events.isEmpty()) {
            return Validation(Status.NOT_APPLICABLE, "Q4_K_DECODE_TRACE_MISSING")
        }

        val incomplete = events.firstOrNull { fields ->
            fields["selected"].isNullOrBlank() || fields["reason"].isNullOrBlank()
        }
        if (incomplete != null) {
            return Validation(Status.INVALID, "INCOMPLETE_Q4_K_DECODE_TRACE")
        }
        val unknownPath = events.firstOrNull { fields ->
            fields["selected"] != BASE_PATH && fields["selected"] != MMVQ_PATH &&
                fields["selected"] != "UNAVAILABLE"
        }
        if (unknownPath != null) {
            return Validation(Status.INVALID, "UNKNOWN_Q4_K_KERNEL_PATH")
        }
        val unavailable = events.firstOrNull { fields ->
            fields["selected"] == "UNAVAILABLE" ||
                fields["reason"]?.contains("pipeline_unavailable_base_pipeline_unavailable") == true
        }
        if (unavailable != null) {
            return Validation(Status.INVALID, "NO_COMPATIBLE_VULKAN_DMMV_PIPELINE")
        }

        val fallbacks = events.filter { it["reason"] == FALLBACK_REASON }
        if (fallbacks.isEmpty()) {
            return Validation(Status.NOT_APPLICABLE, "NO_Q8_1_PIPELINE_FALLBACK_OBSERVED")
        }

        val invalid = fallbacks.firstOrNull { fields ->
            !isValidFallback(
                selectedKernelPath = fields["selected"],
                intDotAvailable = fields["intDot"],
                q8Eligible = fields["q8_1Eligible"],
                q8EligibilityReason = fields["q8_1Eligibility"],
                quantizeY = fields["quantizeY"],
                src1Type = fields["src1Type"],
                yKernelType = fields["yKernelType"],
                batchColumns = fields["batchColumns"],
                q8MatvecAvailable = fields["q8_1MatvecAvailable"],
                q8QuantizerAvailable = fields["q8_1QuantizeAvailable"],
                baseMatvecAvailable = fields["baseMatvecAvailable"],
                fallbackStage = fields["fallbackStage"]
            )
        }
        return if (invalid == null) {
            Validation(Status.VALID, "COMPATIBLE_F32_BASE_DMMV_CONFIRMED", fallbacks.size)
        } else {
            Validation(Status.INVALID, "FALLBACK_KERNEL_OR_QUANTIZATION_EVIDENCE_INVALID", fallbacks.size)
        }
    }

    private fun parseDecodeEvent(line: String): Map<String, String>? {
        val payload = line.substringAfter(DECODE_MARKER, missingDelimiterValue = "")
        if (payload.isBlank()) return null
        return payload.split(',').mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) null
            else item.substring(0, separator).trim() to item.substring(separator + 1).trim()
        }.toMap()
    }

    private fun isValidFallback(
        selectedKernelPath: String?,
        intDotAvailable: String?,
        q8Eligible: String?,
        q8EligibilityReason: String?,
        quantizeY: String?,
        src1Type: String?,
        yKernelType: String?,
        batchColumns: String?,
        q8MatvecAvailable: String?,
        q8QuantizerAvailable: String?,
        baseMatvecAvailable: String?,
        fallbackStage: String?
    ): Boolean {
        if (selectedKernelPath != BASE_PATH ||
            intDotAvailable != "1" ||
            q8Eligible != "1" ||
            q8EligibilityReason != "eligible" ||
            quantizeY != "0" ||
            src1Type != "f32" ||
            yKernelType != "f32" ||
            batchColumns != "1" ||
            baseMatvecAvailable != "1"
        ) return false

        return when (fallbackStage) {
            "matvec_pipeline" -> q8MatvecAvailable == "0" && q8QuantizerAvailable == "0"
            "quantizer_pipeline" -> q8MatvecAvailable == "1" && q8QuantizerAvailable == "0"
            else -> false
        }
    }
}
