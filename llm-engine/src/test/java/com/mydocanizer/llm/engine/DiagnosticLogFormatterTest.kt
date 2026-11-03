package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFormatterTest {

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
