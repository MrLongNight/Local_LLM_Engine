package com.mydocanizer.llm.engine

/**
 * Parses the JNI model metadata string. New writers place architecture in an
 * explicit ARCH= field before the backend; the legacy five-field format keeps
 * the semicolon-containing backend as its final field.
 */
internal data class ParsedNativeModelMetadata(
    val description: String,
    val parameterCount: Long,
    val contextLengthTrain: Int,
    val sizeBytes: Long,
    val architecture: String,
    val activeBackend: String
)

internal object NativeModelMetadataParser {
    fun parse(
        rawMetadata: String,
        fallbackBackend: String,
        fallbackSizeBytes: Long
    ): ParsedNativeModelMetadata {
        val fields = rawMetadata.split(';', limit = 6)
        val hasArchitecture = fields.size >= 6 &&
            fields.getOrNull(4)?.startsWith("ARCH=", ignoreCase = true) == true
        val legacyFields = if (hasArchitecture) fields else rawMetadata.split(';', limit = 5)
        return if (hasArchitecture) {
            ParsedNativeModelMetadata(
                description = fields.getOrNull(0).orEmpty(),
                parameterCount = fields.getOrNull(1)?.toLongOrNull() ?: 0L,
                contextLengthTrain = fields.getOrNull(2)?.toIntOrNull() ?: 0,
                sizeBytes = fields.getOrNull(3)?.toLongOrNull() ?: fallbackSizeBytes,
                architecture = fields[4].substringAfter('=', missingDelimiterValue = "").ifBlank { "GGUF" },
                activeBackend = fields.getOrNull(5)?.ifBlank { fallbackBackend } ?: fallbackBackend
            )
        } else {
            ParsedNativeModelMetadata(
                description = legacyFields.getOrNull(0).orEmpty(),
                parameterCount = legacyFields.getOrNull(1)?.toLongOrNull() ?: 0L,
                contextLengthTrain = legacyFields.getOrNull(2)?.toIntOrNull() ?: 0,
                sizeBytes = legacyFields.getOrNull(3)?.toLongOrNull() ?: fallbackSizeBytes,
                architecture = "GGUF",
                activeBackend = legacyFields.getOrNull(4)?.ifBlank { fallbackBackend } ?: fallbackBackend
            )
        }
    }
}
