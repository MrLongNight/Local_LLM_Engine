package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFormatterTest {

    @Test
    fun snapshotCapturesPreviousAndCurrentLogsAndReportsExactByteCount() {
        val directory = createTempDir(prefix = "llm-diagnostics-test")
        try {
            val previous = File(directory, "previous.log").apply {
                writeText("older-event\\n")
            }
            val current = File(directory, "current.log").apply {
                writeText("newer-event\\n")
            }

            val snapshot = DiagnosticLogSnapshotter.capture(previous, current)

            assertEquals("older-event\\n", String(snapshot.previous, Charsets.UTF_8))
            assertEquals("newer-event\\n", String(snapshot.current, Charsets.UTF_8))
            assertEquals(
                (previous.length() + current.length()),
                snapshot.totalBytes
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun snapshotHandlesMissingLogFilesWithoutFailing() {
        val directory = createTempDir(prefix = "llm-diagnostics-empty")
        try {
            val snapshot = DiagnosticLogSnapshotter.capture(
                File(directory, "missing-previous.log"),
                File(directory, "missing-current.log")
            )
            assertEquals(0L, snapshot.totalBytes)
            assertTrue(snapshot.previous.isEmpty())
            assertTrue(snapshot.current.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun formatLineUsesStableTabSeparatedFieldsAndUtcTimestamp() {
        val line = DiagnosticLogFormatter.formatLine(
            "2026-11-03T12:00:00.000Z",
            "ERROR",
            "inference",
            "INFERENCE_FAILED",
            "nativeCode=5"
        )

        assertTrue(line.startsWith("2026-11-03T12:00:00.000Z\tERROR\tinference\tINFERENCE_FAILED\t"))
        assertTrue(line.endsWith("\n"))
        assertEquals(4, line.count { it == '\t' })
    }

    @Test
    fun sanitizeRemovesLineBreaksAndTabsFromDetails() {
        val sanitized = DiagnosticLogFormatter.sanitize("first\tfield\nsecond\rthird")

        assertFalse(sanitized.contains('\t'))
        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\r'))
        assertTrue(sanitized.contains("first field second third"))
    }

    @Test
    fun sanitizeRedactsLocalPathsAndContentUris() {
        val sanitized = DiagnosticLogFormatter.sanitize(
            "path=/data/user/0/example/files/model.gguf uri=content://provider/document/123"
        )

        assertFalse(sanitized.contains("/data/user/0"))
        assertFalse(sanitized.contains("content://provider"))
        assertTrue(sanitized.contains("<local-path-redacted>"))
        assertTrue(sanitized.contains("<content-uri-redacted>"))
    }

    @Test
    fun sanitizeRedactsRemoteUrlsAndBoundsMessageSize() {
        val sanitized = DiagnosticLogFormatter.sanitize(
            "download=https://example.test/model.gguf?token=secret " + "x".repeat(3000)
        )

        assertFalse(sanitized.contains("https://"))
        assertFalse(sanitized.contains("token=secret"))
        assertTrue(sanitized.length <= 2500)
    }
}
