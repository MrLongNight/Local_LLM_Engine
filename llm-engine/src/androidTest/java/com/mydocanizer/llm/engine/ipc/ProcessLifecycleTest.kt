package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.TimeoutConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessLifecycleTest : InferenceServiceProcessTestBase() {

    @Test
    fun inferenceClientReconnectsAfterRemoteInferenceProcessDies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        val client = InferenceClient(context)
        var directBinding: BoundInferenceService? = null
        try {
            val initialization = client.initialize()
            assertTrue("InferenceClient must bind and initialize", initialization.isSuccess)
            val originalPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient did not publish its service PID")
            assertNotEquals("Inference service must use a dedicated process", Process.myPid(), originalPid)

            directBinding = bindInferenceService(context)
            assertEquals("Both connections must refer to the same inference service", originalPid, directBinding.pid)

            directBinding.service.killProcess()
            assertTrue(
                "Direct Binder must observe the killed service",
                directBinding.disconnected.await(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue("Old inference PID must exit", awaitProcessExit(originalPid, PROCESS_EXIT_TIMEOUT_MS))

            val recoveryDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            var recoveredPid = 0
            while (SystemClock.elapsedRealtime() < recoveryDeadline) {
                val pid = client.remotePid.value
                if (pid != null && pid > 0 && client.state.value == EngineState.Ready) {
                    recoveredPid = pid
                    break
                }
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue("InferenceClient must reconnect and return to Ready", recoveredPid > 0)
            // The old PID is already confirmed absent. Linux may reuse numeric PIDs,
            // so recovery is identified by Ready state and a live, non-app PID.
            assertNotEquals("Recovered inference process must remain separate from the app", Process.myPid(), recoveredPid)
            assertTrue("Recovered inference PID must exist", File("/proc/$recoveredPid").exists())
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { context.unbindService(binding.connection) }
            }
        }
    }

    @Test
    fun inferenceClientReloadsModelAndRunsExactlyOneInferenceAfterServiceProcessDies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        val appPid = Process.myPid()
        val modelFile = File(context.cacheDir, "tiny1m-service-reconnect.gguf")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("tiny1m.Q4_K_M.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        assertTrue("Tiny GGUF fixture must be present and non-empty", modelFile.isFile && modelFile.length() > 0L)

        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 15_000L,
            modelLoadMs = 45_000L,
            prefillMs = 30_000L,
            firstTokenMs = 30_000L,
            interTokenMs = 10_000L,
            totalInferenceMs = 60_000L,
            abortGracePeriodMs = 3_000L,
            reconnectMs = 15_000L
        )
        val config = EngineConfig(
            contextSize = 256,
            batchSize = 64,
            threads = 2,
            temperature = 0.0f,
            topP = 1.0f,
            maxPredictTokens = 12,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            microBatchSize = 16,
            timeoutConfig = timeoutConfig,
            reloadAfterAbort = ReloadAfterAbort.EAGER
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        var originalBinding: BoundInferenceService? = null
        var recoveredBinding: BoundInferenceService? = null
        try {
            val initialization = client.initialize()
            assertTrue("InferenceClient must initialize: " + initialization.exceptionOrNull(), initialization.isSuccess)

            val loaded = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
            assertTrue("Tiny CPU GGUF must load: " + loaded.exceptionOrNull(), loaded.isSuccess)
            assertTrue(
                "The reconnect test must use the CPU backend; backend=" + loaded.getOrThrow().activeBackend,
                loaded.getOrThrow().activeBackend.contains("CPU", ignoreCase = true)
            )
            assertEquals(modelFile.absolutePath, client.currentModel.value?.path)

            val originalPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient did not publish its model-owning service PID")
            assertNotEquals("Native model must live outside the app process", appPid, originalPid)

            val firstBinding = bindInferenceService(context)
            originalBinding = firstBinding
            assertEquals("Client and direct Binder must share the model-owning service", originalPid, firstBinding.pid)
            assertEquals("Model must be loaded before the service is terminated", "MODEL_LOADED", firstBinding.service.status)

            firstBinding.service.killProcess()
            assertTrue(
                "Direct Binder must observe service-process death",
                firstBinding.disconnected.await(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue("Original inference PID must exit", awaitProcessExit(originalPid, PROCESS_EXIT_TIMEOUT_MS))

            withTimeout(timeoutConfig.reconnectMs + timeoutConfig.modelLoadMs + 10_000L) {
                while (true) {
                    val pid = client.remotePid.value
                    val model = client.currentModel.value
                    if (pid != null && pid > 0 &&
                        pid != appPid &&
                        client.state.value is EngineState.ModelLoaded &&
                        model?.path == modelFile.absolutePath &&
                        File("/proc/$pid").exists()
                    ) {
                        break
                    }
                    delay(PROCESS_POLL_INTERVAL_MS)
                }
            }

            val recoveredPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient reconnected without publishing a service PID")
            assertNotEquals("Recovered inference process must remain separate from the app", appPid, recoveredPid)
            assertTrue("Recovered inference process must be alive", File("/proc/$recoveredPid").exists())
            assertEquals("Remembered GGUF must be restored after reconnect", modelFile.absolutePath, client.currentModel.value?.path)

            val secondBinding = bindInferenceService(context)
            recoveredBinding = secondBinding
            assertEquals("Direct Binder must connect to the client's recovered service", recoveredPid, secondBinding.pid)
            assertEquals("EAGER recovery must reload the model before inference", "MODEL_LOADED", secondBinding.service.status)

            val events = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) {
                client.streamChat(
                    InferenceRequest.single("Write one short sentence.", maxPredictTokens = 12)
                        .copy(timeoutConfig = timeoutConfig)
                ).toList()
            }
            val terminalEvents = events.count {
                it is InferenceEvent.Completed ||
                    it is InferenceEvent.Cancelled ||
                    it is InferenceEvent.Error
            }
            val completed = events.filterIsInstance<InferenceEvent.Completed>()
            assertEquals("The single follow-up request must emit exactly one terminal event", 1, terminalEvents)
            assertEquals("The single follow-up request must complete exactly once", 1, completed.size)
            assertTrue("The follow-up must not be cancelled", events.none { it is InferenceEvent.Cancelled })
            assertTrue("The follow-up must not fail", events.none { it is InferenceEvent.Error })
            assertTrue("The follow-up must stream at least one token", events.any { it is InferenceEvent.Token })

            val result = completed.single().result
            assertTrue(
                "Follow-up must finish normally, not inherit a previous process failure: " + result.finishReason,
                result.finishReason == FinishReason.STOP || result.finishReason == FinishReason.MAX_TOKENS
            )
            assertTrue("Follow-up must produce generated tokens", result.tokensGenerated > 0)
            assertTrue("Follow-up must produce visible model output", result.text.isNotBlank())
            assertEquals("Follow-up must keep the recovered inference process", recoveredPid, client.remotePid.value)
            assertEquals("Follow-up must retain the reloaded GGUF", modelFile.absolutePath, client.currentModel.value?.path)
            assertEquals("Service must remain model-loaded after the single follow-up", "MODEL_LOADED", secondBinding.service.status)
        } finally {
            client.close()
            originalBinding?.let { binding -> runCatching { context.unbindService(binding.connection) } }
            recoveredBinding?.let { binding -> runCatching { context.unbindService(binding.connection) } }
            modelFile.delete()
        }
    }

    @Test
    fun serviceRunsInDedicatedProcessCanBeKilledAndBoundAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        val appPid = Process.myPid()

        val first = bindInferenceService(context)
        try {
            assertTrue("Inference service must report a valid PID", first.pid > 0)
            assertNotEquals("Inference service must not run in the app process", appPid, first.pid)

            // AIDL killProcess is one-way: the call must return without waiting for service cleanup.
            first.service.killProcess()

            assertTrue(
                "Binder must disconnect after the inference process is killed",
                first.disconnected.await(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "The original inference PID must disappear from /proc",
                awaitProcessExit(first.pid, PROCESS_EXIT_TIMEOUT_MS)
            )
        } finally {
            runCatching { context.unbindService(first.connection) }
        }

        val second = bindInferenceService(context)
        try {
            assertTrue("Rebound inference service must report a valid PID", second.pid > 0)
            assertNotEquals("Rebound service must stay outside the app process", appPid, second.pid)
        } finally {
            second.service.killProcess()
            assertTrue(
                "Second inference process must also terminate",
                awaitProcessExit(second.pid, PROCESS_EXIT_TIMEOUT_MS)
            )
            runCatching { context.unbindService(second.connection) }
        }
    }
}
