package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceTimeoutException
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.TimeoutConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GgufRecoveryTest : InferenceServiceProcessTestBase() {

    @Test
    fun realGgufIsReloadedAndFollowupInferenceSucceedsAfterTimeoutKill() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        val appPid = Process.myPid()
        val appDiagnosticsFile = resetAppProcessDiagnostics(context, appPid)

        // The workflow downloads this pinned 731 kB Q4_K_M fixture into androidTest/assets.
        // It is a real Llama GGUF used only for load/reload/inference lifecycle verification.
        val modelFile = File(context.cacheDir, "tiny1m.Q4_K_M.gguf")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("tiny1m.Q4_K_M.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        assertTrue("Tiny GGUF fixture must be present and non-empty", modelFile.isFile && modelFile.length() > 0L)

        val timeoutConfig = TimeoutConfig(
            // Cold Android emulator service startup is materially slower than an
            // already-bound process. Match the process-binding helper's 15s budget.
            serviceBindMs = 15_000L,
            modelLoadMs = 30_000L,
            prefillMs = 1_500L,
            firstTokenMs = 10_000L,
            interTokenMs = 10_000L,
            totalInferenceMs = 30_000L,
            abortGracePeriodMs = 150L,
            reconnectMs = 20_000L
        )
        val config = EngineConfig(
            contextSize = 256,
            batchSize = 64,
            threads = 2,
            temperature = 0.0f,
            topP = 1.0f,
            maxPredictTokens = 16,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            timeoutConfig = timeoutConfig,
            // This test explicitly validates eager recovery of a real GGUF after process death.
            reloadAfterAbort = ReloadAfterAbort.EAGER
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        var directBinding: BoundInferenceService? = null

        try {
            val initialization = client.initialize()
            assertTrue(
                "InferenceClient must initialize; error=${initialization.exceptionOrNull()}, " +
                    "state=${client.state.value}, remotePid=${client.remotePid.value}, " +
                    "recentLogs=${EngineDiagnostics.getLogs().takeLast(20).joinToString(" | ")}",
                initialization.isSuccess
            )
            val firstLoad = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
            assertTrue("Real tiny GGUF must load before fault injection: ${firstLoad.exceptionOrNull()}",
                firstLoad.isSuccess)
            val loadedModel = firstLoad.getOrThrow()
            assertEquals("Loaded model must be the real test fixture", modelFile.name, loadedModel.name)
            assertEquals(modelFile.absolutePath, loadedModel.path)

            val originalPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient did not publish the model-owning service PID")
            assertNotEquals("Native model must live outside the app process", appPid, originalPid)

            directBinding = bindInferenceService(context)
            assertEquals("Direct Binder must attach to the model-owning process", originalPid, directBinding.pid)
            val readyDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            while (directBinding.service.status != "MODEL_LOADED" &&
                SystemClock.elapsedRealtime() < readyDeadline
            ) {
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
            }
            assertEquals("Real GGUF must remain loaded before abort", "MODEL_LOADED", directBinding.service.status)

            directBinding.service.armNativeInferenceBlockForTest()
            val blockedRequest = InferenceRequest.single(
                userPrompt = "Trigger the native process recovery test",
                maxPredictTokens = 8
            ).copy(timeoutConfig = timeoutConfig)
            val blockedEvents = async(Dispatchers.IO) { client.streamChat(blockedRequest).toList() }

            val blockedDeadline = SystemClock.elapsedRealtime() + timeoutConfig.prefillMs
            var blockedStatus = directBinding.service.status
            while (blockedStatus != "DEBUG_NATIVE_INFERENCE_BLOCKED" &&
                SystemClock.elapsedRealtime() < blockedDeadline
            ) {
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
                blockedStatus = runCatching { directBinding?.service?.status ?: "DISCONNECTED" }
                    .getOrDefault("DISCONNECTED")
            }
            assertEquals(
                "The real-model run must enter the non-cooperative nativeProcessChat hook",
                "DEBUG_NATIVE_INFERENCE_BLOCKED",
                blockedStatus
            )

            val blockedResult = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) { blockedEvents.await() }
            val timeoutEvent = blockedResult.filterIsInstance<InferenceEvent.Error>().firstOrNull()
            assertTrue("Blocked native call must end as a timeout error",
                timeoutEvent?.throwable is InferenceTimeoutException)
            assertTrue("Old service Binder must disconnect after hard abort",
                directBinding.disconnected.await(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("Original model-owning service PID must exit",
                awaitProcessExit(originalPid, PROCESS_EXIT_TIMEOUT_MS))
            assertEquals("App process must survive native process termination", appPid, Process.myPid())

            val diagnosticsDeadline = SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MS
            var abortConfirmed = false
            while (SystemClock.elapsedRealtime() < diagnosticsDeadline && !abortConfirmed) {
                abortConfirmed = runCatching {
                    appDiagnosticsFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8)
                        ?.any { line ->
                            line.contains("[ipc_client] PROCESS_ABORT_CONFIRMED") &&
                                line.contains("Inference PID=$originalPid")
                        } == true
                }.getOrDefault(false)
                if (!abortConfirmed) delay(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue(
                "App-process log must persist kill confirmation for PID=$originalPid; " +
                    "fileExists=${appDiagnosticsFile.isFile}, fileBytes=${appDiagnosticsFile.length()}, " +
                    "inMemory=${EngineDiagnostics.getLogs().lastOrNull { it.contains("PROCESS_ABORT_CONFIRMED") }}",
                abortConfirmed
            )

            // The client must automatically reopen the same source/config in a fresh process.
            val recoveryDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            var recoveredPid = 0
            var recoveredModelPath: String? = null
            while (SystemClock.elapsedRealtime() < recoveryDeadline) {
                val pid = client.remotePid.value
                val model = client.currentModel.value
                if (pid != null && pid > 0 && pid != originalPid && pid != appPid &&
                    client.state.value is EngineState.ModelLoaded && model != null
                ) {
                    recoveredPid = pid
                    recoveredModelPath = model.path
                    break
                }
                delay(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue(
                "Client must reconnect to a new inference process; state=${client.state.value}, " +
                    "remotePid=${client.remotePid.value}, currentModel=${client.currentModel.value?.path}, " +
                    "recentEvents=${EngineDiagnostics.getLogs().takeLast(60).joinToString(" | ")}, " +
                    "appLogTail=${runCatching { appDiagnosticsFile.readLines(Charsets.UTF_8).takeLast(60).joinToString(" | ") }.getOrDefault("unavailable")}",
                recoveredPid > 0
            )
            assertTrue("Replacement inference PID must exist", File("/proc/$recoveredPid").exists())
            assertEquals("Recovery must reload the exact original GGUF source",
                modelFile.absolutePath, recoveredModelPath)

            // Keep the strict timeout above for the deliberately blocked JNI fault
            // injection. Recovery is a separate, real inference on a cold replacement
            // process and must not inherit the 1.5s fault-injection prefill deadline.
            val recoveryTimeoutConfig = timeoutConfig.copy(
                prefillMs = 30_000L,
                firstTokenMs = 30_000L,
                interTokenMs = 10_000L,
                totalInferenceMs = 60_000L
            )
            val followup = withTimeout(recoveryTimeoutConfig.totalInferenceMs + 5_000L) {
                client.generateChat(
                    InferenceRequest.single(
                        userPrompt = "Write one short sentence.",
                        maxPredictTokens = 12
                    ).copy(timeoutConfig = recoveryTimeoutConfig)
                )
            }
            assertTrue("A real follow-up inference must succeed after reload: ${followup.exceptionOrNull()}",
                followup.isSuccess)
            assertTrue(
                "Follow-up must generate at least one token from the reloaded GGUF",
                followup.getOrThrow().tokensGenerated > 0
            )
            assertEquals("The same model source must remain active after follow-up",
                modelFile.absolutePath, client.currentModel.value?.path)
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { context.unbindService(binding.connection) }
            }
            modelFile.delete()
        }
    }
}
