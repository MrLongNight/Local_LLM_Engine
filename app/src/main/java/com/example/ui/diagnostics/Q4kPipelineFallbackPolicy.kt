package com.example.ui.diagnostics

/**
 * Validates run-correlated Q4_K Vulkan decode evidence. A reported fallback is
 * accepted only when the failed Q8_1 component and the compatible F32 base path
 * are both proven by native runtime fields.
 */
internal object Q4kPipelineFallbackPolicy {
    const val FALLBACK_REASON = "q8_1_pipeline_unavailable_fallback"
    const val BASE_PATH = "BASE_TYPE_MMV"
    private const val MMVQ_PATH = "MMVQ_INTEGER_DOT"
    private const val UNAVAILABLE_PATH = "UNAVAILABLE"
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

        if (events.any { it["selected"].isNullOrBlank() || it["reason"].isNullOrBlank() }) {
            return Validation(Status.INVALID, "INCOMPLETE_Q4_K_DECODE_TRACE")
        }
        if (events.any { it["selected"] == UNAVAILABLE_PATH }) {
            return Validation(Status.INVALID, "NO_COMPATIBLE_VULKAN_DMMV_PIPELINE")
        }
        if (events.any { it["selected"] != BASE_PATH && it["selected"] != MMVQ_PATH }) {
            return Validation(Status.INVALID, "UNKNOWN_Q4_K_KERNEL_PATH")
        }

        val fallbackCount = events.count { it["reason"] == FALLBACK_REASON }
        val invalid = events.firstOrNull { fields ->
            if (fields["reason"] == FALLBACK_REASON) {
                !isValidFallback(fields)
            } else {
                !isValidNormalPath(fields)
            }
        }
        return if (invalid == null) {
            Validation(
                Status.VALID,
                if (fallbackCount > 0) "COMPATIBLE_F32_BASE_DMMV_FALLBACK_CONFIRMED"
                else "Q4_K_VULKAN_DECODE_PATH_CONFIRMED",
                fallbackCount
            )
        } else {
            Validation(
                Status.INVALID,
                if (fallbackCount > 0) "FALLBACK_KERNEL_OR_QUANTIZATION_EVIDENCE_INVALID"
                else "Q4_K_DECODE_KERNEL_EVIDENCE_INVALID",
                fallbackCount
            )
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

    private fun isValidFallback(fields: Map<String, String>): Boolean {
        if (fields["selected"] != BASE_PATH ||
            fields["intDot"] != "1" ||
            fields["q8_1Eligible"] != "1" ||
            fields["q8_1Eligibility"] != "eligible" ||
            fields["quantizeY"] != "0" ||
            fields["src1Type"] != "f32" ||
            fields["yKernelType"] != "f32" ||
            fields["batchColumns"] != "1" ||
            fields["baseMatvecAvailable"] != "1"
        ) return false

        return when (fields["fallbackStage"]) {
            "matvec_pipeline" ->
                fields["q8_1MatvecAvailable"] == "0" && fields["q8_1QuantizeAvailable"] == "0"
            "quantizer_pipeline" ->
                fields["q8_1MatvecAvailable"] == "1" && fields["q8_1QuantizeAvailable"] == "0"
            else -> false
        }
    }

    private fun isValidNormalPath(fields: Map<String, String>): Boolean {
        return when (fields["selected"]) {
            BASE_PATH ->
                fields["quantizeY"] == "0" &&
                    fields["q8_1MatvecAvailable"] == "0" &&
                    fields["q8_1QuantizeAvailable"] == "0" &&
                    fields["baseMatvecAvailable"] == "1" &&
                    fields["fallbackStage"] == "none" &&
                    fields["yKernelType"] in setOf("f32", "f16")
            MMVQ_PATH ->
                fields["intDot"] == "1" &&
                    fields["q8_1Eligible"] == "1" &&
                    fields["q8_1Eligibility"] == "eligible" &&
                    fields["quantizeY"] == "1" &&
                    fields["src1Type"] == "f32" &&
                    fields["yKernelType"] == "q8_1" &&
                    fields["batchColumns"] == "1" &&
                    fields["q8_1MatvecAvailable"] == "1" &&
                    fields["q8_1QuantizeAvailable"] == "1" &&
                    fields["baseMatvecAvailable"] == "0" &&
                    fields["fallbackStage"] == "none"
            else -> false
        }
    }
}
