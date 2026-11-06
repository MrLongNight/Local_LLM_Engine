package com.mydocanizer.llm.engine.internal

import java.io.File
import kotlinx.coroutines.runBlocking
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

    @Test
    fun filePathResolutionDoesNotRequireAnAndroidContext() = runBlocking {
        val file = File.createTempFile("valid-file-path-", ".gguf")
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00))
            val context = object : android.content.ContextWrapper(null) {}
            val resolved = ModelSourceResolver.resolveToFile(
                context,
                com.mydocanizer.llm.engine.ModelSource.FilePath(file.absolutePath)
            )
            assertTrue(resolved.canonicalFile == file.canonicalFile)
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
