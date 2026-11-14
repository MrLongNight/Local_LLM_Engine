package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.TimeoutConfig
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
    fun contentUriLoadsAndRunsThroughOriginalBinderDescriptor() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertInferenceServiceManifest(appContext)

        val modelFile = File(testContext.cacheDir, "provider-${System.nanoTime()}.gguf")
        testContext.assets.open("tiny1m.Q4_K_M.gguf").use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("Provider test GGUF must be staged in the provider's private cache",
            modelFile.isFile && modelFile.length() > 0L)

        val modelUri = Uri.parse(
            "content://com.mydocanizer.llm.engine.test-model-provider/${modelFile.name}"
        )
        val resolverContext = object : ContextWrapper(appContext) {
            override fun getContentResolver(): ContentResolver = testContext.contentResolver
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 15_000L,
            modelLoadMs = 30_000L,
            prefillMs = 30_000L,
            firstTokenMs = 30_000L,
            interTokenMs = 10_000L,
            totalInferenceMs = 60_000L,
            abortGracePeriodMs = 500L,
            reconnectMs = 20_000L
        )
        val config = EngineConfig(
            contextSize = 256,
            batchSize = 64,
            threads = 2,
            temperature = 0f,
            topP = 1f,
            maxPredictTokens = 16,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            timeoutConfig = timeoutConfig
        )
        val client = InferenceClient(resolverContext, timeoutConfig = timeoutConfig)
        var directBinding: BoundInferenceService? = null
        try {
            assertTrue("Inference service must initialize for ContentUri loading",
                client.initialize().isSuccess)

            val loaded = client.loadModel(
                ModelSource.ContentUri(modelUri.toString(), modelFile.name),
                config
            )
            assertTrue("A provider-backed GGUF must load via the transferred PFD: ${loaded.exceptionOrNull()}",
                loaded.isSuccess)
            val model = loaded.getOrThrow()
            assertEquals("Provider display name must remain the model name", modelFile.name, model.name)
            assertEquals("The public model identity must remain the original content URI",
                modelUri.toString(), model.path)
            assertEquals("The provider descriptor size must be preserved",
                modelFile.length(), model.sizeBytes)

            assertTrue(
                "Client diagnostics must prove direct descriptor use without a copy",
                EngineDiagnostics.getLogs().any { line ->
                    line.contains("MODEL_SOURCE_DIRECT_DESCRIPTOR") &&
                        line.contains("copy=false") &&
                        line.contains("displayName=${modelFile.name}")
                }
            )

            directBinding = bindInferenceService(appContext)
            assertEquals(client.remotePid.value, directBinding.pid)
            val loadedDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            var serviceStatus = directBinding.service.status
            while (serviceStatus != "MODEL_LOADED" &&
                SystemClock.elapsedRealtime() < loadedDeadline
            ) {
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
                serviceStatus = runCatching { directBinding?.service?.status ?: "DISCONNECTED" }
                    .getOrDefault("DISCONNECTED")
            }
            assertEquals("Remote :inference service must own the loaded model descriptor",
                "MODEL_LOADED", serviceStatus)

            val followup = client.generateChat(
                InferenceRequest.single(
                    userPrompt = "Write one short sentence.",
                    maxPredictTokens = 12
                ).copy(timeoutConfig = timeoutConfig)
            )
            assertTrue("Inference must still work while the remote mmap descriptor is retained: ${followup.exceptionOrNull()}",
                followup.isSuccess)
            assertTrue("Provider-backed GGUF must generate at least one token",
                followup.getOrThrow().tokensGenerated > 0)

            val unloaded = client.unloadModel()
            assertTrue("Unloading must release the service-owned descriptor: ${unloaded.exceptionOrNull()}",
                unloaded.isSuccess)
            assertEquals(null, client.currentModel.value)
            assertEquals("READY", directBinding.service.status)

            val reloaded = client.loadModel(
                ModelSource.ContentUri(modelUri.toString(), modelFile.name),
                config
            )
            assertTrue("The original provider URI must be reopenable after unload: ${reloaded.exceptionOrNull()}",
                reloaded.isSuccess)
            assertEquals(modelUri.toString(), client.currentModel.value?.path)
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { appContext.unbindService(binding.connection) }
            }
            modelFile.delete()
        }
    }

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
