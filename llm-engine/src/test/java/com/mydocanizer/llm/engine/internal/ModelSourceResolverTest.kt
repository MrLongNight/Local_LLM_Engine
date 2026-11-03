package com.mydocanizer.llm.engine.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSourceResolverTest {
    @Test
    fun sha256FileReturnsTheFullContentDigest() {
        val file = File.createTempFile("llm-model-hash", ".bin")
        try {
            file.writeBytes("local-model-data".toByteArray(Charsets.UTF_8))
            assertEquals(
                "b8cde1410dc64ba1a99cf1c16585e8f567f8bedd5f46b86412dd255b2d7eae61",
                ModelSourceResolver.sha256File(file)
            )
        } finally {
            file.delete()
        }
    }
}
