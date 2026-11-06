package com.mydocanizer.llm.engine.internal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceResolverTest {

    @Test
    fun acceptsValidGgufMagicHeader() {
        val file = File.createTempFile("valid-gguf-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00))
            ModelSourceResolver.validateGgufMagic(file)
            assertTrue(file.length() >= 4L)
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonGgufFileBeforeNativeLoad() {
        val file = File.createTempFile("invalid-gguf-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0x4D, 0x5A, 0x00, 0x00))
            ModelSourceResolver.validateGgufMagic(file)
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedGgufHeader() {
        val file = File.createTempFile("truncated-gguf-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55))
            ModelSourceResolver.validateGgufMagic(file)
        } finally {
            file.delete()
        }
    }
}
