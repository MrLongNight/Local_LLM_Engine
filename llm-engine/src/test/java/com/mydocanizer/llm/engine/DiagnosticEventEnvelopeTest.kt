package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventEnvelopeTest {

    @Test
    fun versionedEnvelopeCarriesStableEventPidAndRunCorrelation() {
        val line = DiagnosticEventEnvelope.format(
            timestamp = "2026-11-15 12:00:00.000",
            level = "INFO",
            tag = "inference",
            event = "TOKEN_STEP_METRIC",
            details = "backend=Vulkan,runId=decode-run-42,index=3",
            pid = 8123,
            sequence = 7
        )

        assertTrue(line.contains("schemaVersion=1"))
        assertTrue(line.contains("eventId=8123-7"))
        assertTrue(line.contains("pid=8123"))
        assertTrue(line.contains("correlationType=runId"))
        assertTrue(line.contains("correlationId=decode-run-42"))
        assertTrue(line.endsWith("backend=Vulkan,runId=decode-run-42,index=3"))
    }

    @Test
    fun requestCorrelationIsUsedWhenNoRunIdExists() {
        val line = DiagnosticEventEnvelope.format(
            timestamp = "timestamp",
            level = "INFO",
            tag = "ipc",
            event = "INFERENCE_STARTED",
            details = "request=request-19,backend=CPU",
            pid = 91,
            sequence = 1
        )

        assertTrue(line.contains("correlationType=request"))
        assertTrue(line.contains("correlationId=request-19"))
    }

    @Test
    fun eventDetailsAreBoundedAndMultilineValuesRemainOnePhysicalRow() {
        val details = "line1\n" + "x".repeat(DiagnosticEventEnvelope.MAX_DETAILS_CHARS + 100)
        val line = DiagnosticEventEnvelope.format(
            timestamp = "timestamp",
            level = "ERROR",
            tag = "engine",
            event = "FAILURE",
            details = details,
            pid = 5,
            sequence = 2
        )

        val envelopeAndSeparator = line.substringAfter(" - ")
        val boundedDetails = envelopeAndSeparator.substringAfter("correlationId=none,")
        assertEquals(DiagnosticEventEnvelope.MAX_DETAILS_CHARS, boundedDetails.length)
        assertTrue(boundedDetails.endsWith("...[truncated]"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun missingOrMalformedCorrelationDoesNotInventAnIdentifier() {
        val line = DiagnosticEventEnvelope.format(
            timestamp = "timestamp",
            level = "INFO",
            tag = "engine",
            event = "IDLE",
            details = "runId=contains/slash",
            pid = 6,
            sequence = 3
        )

        assertTrue(line.contains("correlationType=none,correlationId=none"))
    }

    @Test
    fun zeroPidIsRepresentedForHostAndRobolectricTestProcesses() {
        val line = DiagnosticEventEnvelope.format(
            timestamp = "timestamp",
            level = "INFO",
            tag = "engine",
            event = "TEST_EVENT",
            details = "",
            pid = 0,
            sequence = 1
        )

        assertTrue(line.contains("eventId=0-1"))
        assertTrue(line.contains("pid=0"))
    }

}
