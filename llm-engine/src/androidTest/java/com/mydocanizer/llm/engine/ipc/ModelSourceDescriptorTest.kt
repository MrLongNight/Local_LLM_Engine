package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.internal.ModelSourceResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSourceDescriptorTest {

    @Test
    fun directSeekableProcDescriptorIsAcceptedWithoutCopying() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceFile = File(context.cacheDir, "direct-saf-descriptor-${System.nanoTime()}.gguf")
        sourceFile.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00, 0x01, 0x02, 0x03))
        val descriptor = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val procPath = "/proc/self/fd/${descriptor.fd}"
            val resolved = ModelSourceResolver.resolveToFile(
                context,
                ModelSource.FilePath(procPath, descriptor.fileDescriptor, descriptor.fd)
            )

            assertEquals("Direct descriptor path must be preserved for native loading", procPath, resolved.absolutePath)
            assertEquals("Descriptor must still refer to the original file", sourceFile.length(), resolved.length())
            assertTrue("The caller-owned SAF descriptor must remain open", descriptor.fileDescriptor.valid())
        } finally {
            descriptor.close()
            sourceFile.delete()
        }
    }

    @Test
    fun directPipeDescriptorIsRejectedWithNoCopyDiagnostic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val procPath = "/proc/self/fd/${pipe[0].fd}"
            val error = runCatching {
                ModelSourceResolver.resolveToFile(
                    context,
                    ModelSource.FilePath(procPath, pipe[0].fileDescriptor, pipe[0].fd)
                )
            }.exceptionOrNull()

            assertTrue("A pipe-backed SAF descriptor must be rejected before native load", error is IllegalArgumentException)
            val errorMessage = error?.message.orEmpty()
            assertTrue(
                "Error must explain seekability/mmap requirement and confirm no copy fallback",
                errorMessage.contains("not a seekable regular file") &&
                    errorMessage.contains("not copied")
            )
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
    }
}
