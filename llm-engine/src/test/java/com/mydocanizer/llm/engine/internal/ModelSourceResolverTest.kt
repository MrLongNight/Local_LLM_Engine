package com.mydocanizer.llm.engine.internal

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    @Test
    fun cancelledSafCopyDeletesItsTemporaryFile() = runBlocking {
        val targetDir = File.createTempFile("cancelled-import-", "").apply {
            delete()
            mkdir()
        }
        lateinit var importJob: Job
        val source = object : InputStream() {
            private var delivered = false

            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered) return -1
                delivered = true
                importJob.cancel()
                val header = byteArrayOf(0x47, 0x47, 0x55, 0x46)
                header.copyInto(buffer, offset)
                return header.size
            }
        }

        try {
            importJob = launch {
                ModelSourceResolver.copyAndPublish(
                    targetDir = targetDir,
                    displayName = "cancelled.gguf",
                    input = source
                )
            }
            importJob.join()

            assertTrue("Import job must be cancelled", importJob.isCancelled)
            assertTrue(
                "Cancelled import must remove its incomplete .part file",
                targetDir.listFiles().isNullOrEmpty()
            )
        } finally {
            targetDir.deleteRecursively()
        }
    }

}
