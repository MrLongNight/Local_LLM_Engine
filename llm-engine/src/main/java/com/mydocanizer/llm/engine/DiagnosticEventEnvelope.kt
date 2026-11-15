package com.mydocanizer.llm.engine

/**
 * Versioned, bounded envelope for Kotlin-side diagnostic events.
 *
 * Native diagnostics retain their native format for compatibility with upstream
 * tooling. Kotlin events carry a stable schema marker and process-local identity;
 * existing run/request identifiers are copied into an explicit correlation field.
 */
internal object DiagnosticEventEnvelope {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_DETAILS_CHARS = 4_096

    private const val TRUNCATION_MARKER = "...[truncated]"
    private val correlationKeys = listOf(
        "runId",
        "requestId",
        "request",
        "gateRunId",
        "diagnosticRunId"
    )
    private val correlationPattern =
        Regex("""(?:^|[,\s])([A-Za-z][A-Za-z0-9_]*)=([A-Za-z0-9_.:-]{1,96})(?=$|[,\s])""")

    fun format(
        timestamp: String,
        level: String,
        tag: String,
        event: String,
        details: String,
        pid: Int,
        sequence: Long
    ): String {
        require(pid >= 0) { "Diagnostic PID must be non-negative." }
        require(sequence > 0L) { "Diagnostic event sequence must be positive." }

        val normalizedDetails = details
            .replace("\r\n", "\\r\\n")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val boundedDetails = if (normalizedDetails.length <= MAX_DETAILS_CHARS) {
            normalizedDetails
        } else {
            normalizedDetails.take(MAX_DETAILS_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        }
        val correlation = correlationPattern.findAll(boundedDetails)
            .firstOrNull { it.groupValues[1] in correlationKeys }
        val correlationType = correlation?.groupValues?.get(1) ?: "none"
        val correlationId = correlation?.groupValues?.get(2) ?: "none"
        val envelope = "schemaVersion=$CURRENT_SCHEMA_VERSION,eventId=$pid-$sequence,pid=$pid," +
            "correlationType=$correlationType,correlationId=$correlationId"

        return if (boundedDetails.isNotBlank()) {
            "$timestamp [$level] [$tag] $event - $envelope,$boundedDetails"
        } else {
            "$timestamp [$level] [$tag] $event - $envelope"
        }
    }
}
