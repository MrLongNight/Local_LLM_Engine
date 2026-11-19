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
import android.os.Process
import android.system.Os
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
class ModelSourceDescriptorTest : InferenceServiceProcessTestBase() {


    @Test
    fun contentUriLoadsAndRunsThroughOriginalBinderDescriptor() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertInferenceServiceManifest(appContext)

        val fixtureName = "provider-${System.nanoTime()}.gguf"
        // The instrumentation provider can resolve either package cache depending on
        // the Android test runner's manifest/process context. Stage identical bytes in
        // both locations so the test exercises descriptor transfer, not cache routing.
        val modelFiles = listOf(testContext.cacheDir, appContext.cacheDir)
            .distinct()
            .map { cacheDir ->
                File(cacheDir, fixtureName).also { file ->
                    testContext.assets.open("tiny1m.Q4_K_M.gguf").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        val modelFile = modelFiles.first()
        assertTrue("Provider test GGUF must be staged in provider-visible caches",
            modelFiles.all { it.isFile && it.length() == modelFile.length() && it.length() > 0L })

        val modelUri = Uri.parse(
            "content://com.mydocanizer.llm.engine.test-model-provider/${modelFile.name}"
        )
        testContext.contentResolver.openFileDescriptor(modelUri, "r").use { providerDescriptor ->
            assertTrue("Provider must return an open descriptor", providerDescriptor != null)
            assertEquals(
                "Provider descriptor stat size must match the staged GGUF byte length",
                modelFile.length(),
                requireNotNull(providerDescriptor).statSize
            )
        }
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
            assertTrue(
                "Native model tensor size must be reported independently from container file size",
                model.sizeBytes > 0L
            )

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
            val followupResult = followup.getOrThrow()
            // The pinned tiny1m fixture can legitimately select EOG as its first token
            // for this provider-backed prompt. A normal STOP is a completed native
            // inference; token-count positivity is covered by the dedicated GGUF recovery test.
            assertTrue(
                "Provider-backed inference must finish normally: ${followupResult.finishReason}",
                followupResult.finishReason == com.mydocanizer.llm.engine.FinishReason.STOP ||
                    followupResult.finishReason == com.mydocanizer.llm.engine.FinishReason.MAX_TOKENS
            )
            assertTrue(
                "Provider-backed inference must report the native execution identity",
                followupResult.nativeRunId.isNotBlank()
            )

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
            val finalUnload = client.unloadModel()
            assertTrue("Final provider reload must release its descriptor: ${finalUnload.exceptionOrNull()}",
                finalUnload.isSuccess)
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { appContext.unbindService(binding.connection) }
            }
            modelFiles.forEach { it.delete() }
        }
    }

    @Test
    fun failedProviderLoadClosesTransferredDescriptorBeforeNextLoad() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val invalidName = "invalid-provider-${System.nanoTime()}.gguf"
        val invalidFiles = listOf(testContext.cacheDir, appContext.cacheDir)
            .distinct()
            .map { File(it, invalidName).also { file -> file.writeBytes(byteArrayOf(0x42, 0x41, 0x44, 0x21)) } }
        val validName = "valid-after-failure-${System.nanoTime()}.gguf"
        val validFiles = listOf(testContext.cacheDir, appContext.cacheDir)
            .distinct()
            .map { cacheDir ->
                File(cacheDir, validName).also { file ->
                    testContext.assets.open("tiny1m.Q4_K_M.gguf").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        val invalidUri = Uri.parse("content://com.mydocanizer.llm.engine.test-model-provider/$invalidName")
        val validUri = Uri.parse("content://com.mydocanizer.llm.engine.test-model-provider/$validName")
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
        val resolverContext = object : ContextWrapper(appContext) {
            override fun getContentResolver(): ContentResolver = testContext.contentResolver
        }
        val client = InferenceClient(resolverContext, timeoutConfig = timeoutConfig)
        var binding: BoundInferenceService? = null
        try {
            assertTrue(client.initialize().isSuccess)
            binding = bindInferenceService(appContext)
            val pid = binding.pid
            val invalidBaseline = descriptorCountFor(pid, invalidFiles)
            val failed = client.loadModel(
                ModelSource.ContentUri(invalidUri.toString(), invalidName),
                config
            )
            assertTrue("Invalid provider GGUF must fail before native model ownership: ${failed.exceptionOrNull()}",
                failed.isFailure)
            awaitDescriptorCount(pid, invalidFiles, expectedCount = invalidBaseline)

            val validBaseline = descriptorCountFor(pid, validFiles)
            val loaded = client.loadModel(
                ModelSource.ContentUri(validUri.toString(), validName),
                config
            )
            assertTrue("A valid provider model must load after the failed descriptor handoff: ${loaded.exceptionOrNull()}",
                loaded.isSuccess)
            awaitDescriptorCount(pid, validFiles, minimumCount = validBaseline + 1)
            assertTrue(client.unloadModel().isSuccess)
            awaitDescriptorCount(pid, validFiles, expectedCount = validBaseline)
        } finally {
            client.close()
            binding?.let { runCatching { appContext.unbindService(it.connection) } }
            invalidFiles.forEach { it.delete() }
            validFiles.forEach { it.delete() }
        }
    }

    @Test
    fun providerDescriptorOwnershipSurvivesReplacementAndCleansUpOnServiceDeath() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val firstName = "provider-first-${System.nanoTime()}.gguf"
        val secondName = "provider-second-${System.nanoTime()}.gguf"
        fun stage(name: String): List<File> =
            listOf(testContext.cacheDir, appContext.cacheDir).distinct().map { cacheDir ->
                File(cacheDir, name).also { file ->
                    testContext.assets.open("tiny1m.Q4_K_M.gguf").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        val firstFiles = stage(firstName)
        val secondFiles = stage(secondName)
        val firstUri = Uri.parse("content://com.mydocanizer.llm.engine.test-model-provider/$firstName")
        val secondUri = Uri.parse("content://com.mydocanizer.llm.engine.test-model-provider/$secondName")
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
        val resolverContext = object : ContextWrapper(appContext) {
            override fun getContentResolver(): ContentResolver = testContext.contentResolver
        }
        val client = InferenceClient(resolverContext, timeoutConfig = timeoutConfig)
        var binding: BoundInferenceService? = null
        try {
            assertTrue(client.initialize().isSuccess)
            binding = bindInferenceService(appContext)
            val originalPid = binding.pid
            val clientPid = Process.myPid()
            assertNotEquals(
                "The Binder inference service must own descriptors in a separate process",
                clientPid,
                originalPid
            )
            val firstBaseline = descriptorCountFor(originalPid, firstFiles)
            val secondBaseline = descriptorCountFor(originalPid, secondFiles)
            val firstClientBaseline = descriptorCountFor(clientPid, firstFiles)
            val secondClientBaseline = descriptorCountFor(clientPid, secondFiles)

            val firstLoaded = client.loadModel(
                ModelSource.ContentUri(firstUri.toString(), firstName),
                config
            )
            assertTrue("First provider model must load: ${firstLoaded.exceptionOrNull()}", firstLoaded.isSuccess)
            // The service must retain both its Binder-delivered PFD and JNI's dup()/FILE*.
            // The client-side PFD is closed as soon as the synchronous Binder handoff returns.
            awaitDescriptorCount(originalPid, firstFiles, minimumCount = firstBaseline + 2)
            awaitDescriptorCount(clientPid, firstFiles, expectedCount = firstClientBaseline)

            val secondLoaded = client.loadModel(
                ModelSource.ContentUri(secondUri.toString(), secondName),
                config
            )
            assertTrue("Replacing a provider model must succeed: ${secondLoaded.exceptionOrNull()}",
                secondLoaded.isSuccess)
            assertEquals(secondUri.toString(), client.currentModel.value?.path)
            awaitDescriptorCount(originalPid, firstFiles, expectedCount = firstBaseline)
            awaitDescriptorCount(originalPid, secondFiles, minimumCount = secondBaseline + 2)
            awaitDescriptorCount(clientPid, secondFiles, expectedCount = secondClientBaseline)

            val replacementInference = client.generateChat(
                InferenceRequest.single("Reply with one short word.").copy(timeoutConfig = timeoutConfig)
            )
            assertTrue("Inference must continue while the replacement descriptor is retained",
                replacementInference.isSuccess)

            val killedPid = client.remotePid.value ?: originalPid
            Process.killProcess(killedPid)

            // Process.killProcess() returns before Android delivers onServiceDisconnected.
            // Wait for a different Binder process before issuing the next request; this
            // EngineConfig intentionally uses LAZY reload, so generateChat() performs the
            // provider-URI restore after the replacement process is connected.
            val recoveryDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < recoveryDeadline) {
                val recoveredPid = client.remotePid.value
                if (recoveredPid != null && recoveredPid != killedPid) break
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue(
                "Client must bind a replacement inference process before retrying; " +
                    "state=${client.state.value},pid=${client.remotePid.value}",
                client.remotePid.value != null && client.remotePid.value != killedPid
            )
            val recoveredInference = client.generateChat(
                InferenceRequest.single("Reply with one short word after recovery.")
                    .copy(timeoutConfig = timeoutConfig)
            )
            assertTrue("Recovery must reopen the original provider URI after service death: ${recoveredInference.exceptionOrNull()}",
                recoveredInference.isSuccess)
            assertEquals(
                "Lazy recovery must preserve the original provider URI as the active model",
                secondUri.toString(),
                client.currentModel.value?.path
            )
            val recoveredPid = client.remotePid.value
                ?: throw AssertionError("Inference service PID missing after descriptor recovery")
            assertTrue("Service death must create a replacement process", recoveredPid != killedPid)
            // A replacement service must again own exactly the two required layers:
            // its Binder PFD and the native FILE* created by dup().
            awaitDescriptorCount(recoveredPid, secondFiles, minimumCount = 2)
            awaitDescriptorCount(clientPid, secondFiles, expectedCount = secondClientBaseline)

            assertTrue("Unloading the recovered model must release its service and native descriptors",
                client.unloadModel().isSuccess)
            awaitDescriptorCount(recoveredPid, secondFiles, expectedCount = 0)
        } finally {
            client.close()
            binding?.let { runCatching { appContext.unbindService(it.connection) } }
            firstFiles.forEach { it.delete() }
            secondFiles.forEach { it.delete() }
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

    private fun descriptorCountFor(pid: Int, files: List<File>): Int =
        files.sumOf { file ->
            val fdDirectory = File("/proc/$pid/fd")
            val expectedTarget = file.canonicalFile
            fdDirectory.listFiles().orEmpty().count { descriptor ->
                runCatching {
                    File(Os.readlink(descriptor.absolutePath)).canonicalFile == expectedTarget
                }.getOrDefault(false)
            }
        }

    private fun awaitDescriptorCount(
        pid: Int,
        files: List<File>,
        expectedCount: Int? = null,
        minimumCount: Int? = null
    ) {
        require((expectedCount == null) != (minimumCount == null))
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var actual = descriptorCountFor(pid, files)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expectedCount?.let { actual == it } == true ||
                minimumCount?.let { actual >= it } == true
            ) return
            SystemClock.sleep(20L)
            actual = descriptorCountFor(pid, files)
        }
        assertTrue(
            "Descriptor count for pid=$pid files=${files.map { it.name }} expected=$expectedCount minimum=$minimumCount actual=$actual",
            expectedCount?.let { actual == it } ?: (actual >= requireNotNull(minimumCount))
        )
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
