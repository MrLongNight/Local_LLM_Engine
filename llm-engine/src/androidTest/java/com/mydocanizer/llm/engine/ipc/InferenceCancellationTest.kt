package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceTimeoutException
import com.mydocanizer.llm.engine.TimeoutConfig
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InferenceCancellationTest : InferenceServiceProcessTestBase() {

    @Test
    fun cooperativeCpuPrefillCancellationKeepsSameModelProcessReusable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        val appPid = Process.myPid()
        val appDiagnosticsFile = resetAppProcessDiagnostics(context, appPid)
        val modelFile = File(context.cacheDir, "tiny1m-cooperative-cancel.gguf")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("tiny1m.Q4_K_M.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        assertTrue("Tiny GGUF fixture must be present and non-empty", modelFile.isFile && modelFile.length() > 0L)

        // CPU uses the pinned llama.cpp abort callback. Vulkan deliberately relies on
        // process-level escalation and is covered by the separate non-cooperative test below.
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 15_000L,
            modelLoadMs = 30_000L,
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
            maxPredictTokens = 16,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            microBatchSize = 16,
            timeoutConfig = timeoutConfig
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        var directBinding: BoundInferenceService? = null
        try {
            val initialization = client.initialize()
            assertTrue("InferenceClient must initialize: ${initialization.exceptionOrNull()}", initialization.isSuccess)
            val loaded = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
            assertTrue("Tiny CPU GGUF must load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
            assertTrue(
                "The test must exercise the CPU abort-callback path; backend=${loaded.getOrThrow().activeBackend}",
                loaded.getOrThrow().activeBackend.contains("CPU", ignoreCase = true)
            )

            val originalPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient did not publish its model-owning service PID")
            assertNotEquals("Native model must live outside the app process", appPid, originalPid)
            directBinding = bindInferenceService(context)
            assertEquals(originalPid, directBinding.pid)
            val loadedDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            var serviceStatus = directBinding.service.status
            while (serviceStatus != "MODEL_LOADED" && SystemClock.elapsedRealtime() < loadedDeadline) {
                delay(PROCESS_POLL_INTERVAL_MS)
                serviceStatus = runCatching { directBinding?.service?.status ?: "DISCONNECTED" }
                    .getOrDefault("DISCONNECTED")
            }
            assertEquals("Real GGUF must remain loaded before cooperative cancellation", "MODEL_LOADED", serviceStatus)

            // A long prompt with small native micro-batches leaves enough real CPU prefill
            // work after the first progress callback for Binder cancellation to reach llama.cpp.
            val longPrompt = buildString {
                repeat(200) { append("hello ") }
            }
            val observed = Collections.synchronizedList(mutableListOf<InferenceEvent>())
            val promptProgressSeen = CompletableDeferred<Unit>()
            val streamJob = launch(Dispatchers.IO) {
                client.streamChat(
                    InferenceRequest.single(longPrompt, maxPredictTokens = 16)
                        .copy(timeoutConfig = timeoutConfig)
                ).collect { event ->
                    observed.add(event)
                    if (event is InferenceEvent.Progress &&
                        event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION
                    ) {
                        promptProgressSeen.complete(Unit)
                    }
                }
            }

            withTimeout(timeoutConfig.prefillMs) { promptProgressSeen.await() }
            client.cancelInference()
            withTimeout(timeoutConfig.prefillMs) { streamJob.join() }

            assertTrue(
                "CPU prefill cancellation must complete cooperatively; events=${observed.joinToString()}",
                observed.any { it is InferenceEvent.Cancelled }
            )
            assertFalse(
                "Cancellation must happen before prompt completion",
                observed.any { it is InferenceEvent.Started || it is InferenceEvent.Completed }
            )
            assertEquals("Cooperative cancellation must keep the original inference PID", originalPid, client.remotePid.value)
            assertEquals("The original model must remain loaded", "MODEL_LOADED", directBinding.service.status)
            assertEquals(modelFile.absolutePath, client.currentModel.value?.path)

            val acknowledgementDeadline = SystemClock.elapsedRealtime() + 5_000L
            var cooperativeAcknowledged = false
            while (SystemClock.elapsedRealtime() < acknowledgementDeadline && !cooperativeAcknowledged) {
                cooperativeAcknowledged = runCatching {
                    appDiagnosticsFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8)
                        ?.any { line ->
                            line.contains("[ipc_client] COOPERATIVE_CANCEL_ACKNOWLEDGED") &&
                                line.contains("reason=explicit_cancel")
                        } == true
                }.getOrDefault(false)
                if (!cooperativeAcknowledged) delay(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue(
                "Client must acknowledge cooperative unwind before the grace deadline escalates",
                cooperativeAcknowledged
            )
            val cancellationLog = appDiagnosticsFile.readLines(Charsets.UTF_8)
            assertFalse(
                "A successful cooperative cancel must not request process termination",
                cancellationLog.any { line ->
                    line.contains("[ipc_client] COOPERATIVE_CANCEL_ESCALATED") ||
                        (line.contains("[ipc_client] PROCESS_KILL_REQUESTED") && line.contains("pid=$originalPid"))
                }
            )

            // The first follow-up must use the same live EngineInstance. A stale Kotlin
            // cancellation latch would incorrectly turn this request into CANCELLED once.
            val followup = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) {
                client.generateChat(
                    InferenceRequest.single("Write one short sentence.", maxPredictTokens = 12)
                        .copy(timeoutConfig = timeoutConfig)
                )
            }
            assertTrue("Same-process follow-up inference must succeed: ${followup.exceptionOrNull()}", followup.isSuccess)
            val followupResult = followup.getOrThrow()
            assertTrue(
                "Follow-up must finish normally, not inherit the previous cancellation: ${followupResult.finishReason}",
                followupResult.finishReason == FinishReason.STOP ||
                    followupResult.finishReason == FinishReason.MAX_TOKENS
            )
            assertTrue(
                "Follow-up must produce visible model output",
                followupResult.tokensGenerated > 0 && followupResult.text.isNotBlank()
            )
            assertEquals("Follow-up must keep the same inference process", originalPid, client.remotePid.value)
            assertEquals("The original GGUF must remain loaded", modelFile.absolutePath, client.currentModel.value?.path)
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { context.unbindService(binding.connection) }
            }
            modelFile.delete()
        }
    }

    @Test
    fun inferenceClientHardAbortsWhileNativeProcessChatIsBlockedAndReconnects() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertInferenceServiceManifest(context)
        // Start with a fresh app-process file so a reused PID cannot satisfy this test
        // with a stale confirmation from an earlier instrumentation execution.
        val appPid = Process.myPid()
        val appDiagnosticsFile = resetAppProcessDiagnostics(context, appPid)

        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 5_000L,
            modelLoadMs = 15_000L,
            prefillMs = 2_500L,
            firstTokenMs = 10_000L,
            interTokenMs = 1_500L,
            totalInferenceMs = 15_000L,
            abortGracePeriodMs = 150L,
            reconnectMs = 5_000L
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        var directBinding: BoundInferenceService? = null
        try {
            assertTrue("InferenceClient must initialize the native engine", client.initialize().isSuccess)
            val originalPid = client.remotePid.value
                ?: throw AssertionError("InferenceClient did not publish its inference PID")
            assertNotEquals("Inference must be outside the app process", appPid, originalPid)

            directBinding = bindInferenceService(context)
            assertEquals(originalPid, directBinding.pid)

            val readyDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            while (directBinding.service.status != "READY" &&
                SystemClock.elapsedRealtime() < readyDeadline
            ) {
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
            }
            assertEquals("Native engine must be ready before arming the JNI hook", "READY", directBinding.service.status)

            directBinding.service.armNativeInferenceBlockForTest()
            val request = InferenceRequest.single("debug-only native process kill test")
                .copy(timeoutConfig = timeoutConfig)
            val events = async(Dispatchers.IO) { client.streamChat(request).toList() }

            val blockedDeadline = SystemClock.elapsedRealtime() + timeoutConfig.prefillMs
            var blockedStatus = directBinding.service.status
            while (blockedStatus != "DEBUG_NATIVE_INFERENCE_BLOCKED" &&
                SystemClock.elapsedRealtime() < blockedDeadline
            ) {
                SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
                blockedStatus = runCatching { directBinding.service.status }.getOrDefault("DISCONNECTED")
            }
            assertEquals(
                "Must enter the deliberately blocked nativeProcessChat JNI frame before timeout",
                "DEBUG_NATIVE_INFERENCE_BLOCKED",
                blockedStatus
            )

            val eventsResult = withTimeout(timeoutConfig.totalInferenceMs + 3_000L) { events.await() }
            val timeoutEvent = eventsResult.filterIsInstance<InferenceEvent.Error>().firstOrNull()
            assertTrue(
                "Client watchdog must report an inference timeout, not coroutine cancellation",
                timeoutEvent?.throwable is InferenceTimeoutException
            )

            assertTrue(
                "Direct Binder connection must disconnect after process death",
                directBinding.disconnected.await(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue("Original JNI-blocked PID must exit", awaitProcessExit(originalPid, PROCESS_EXIT_TIMEOUT_MS))
            assertEquals("App process must survive inference process termination", appPid, Process.myPid())

            // PROCESS_ABORT_CONFIRMED is emitted by the surviving client process after
            // it observes the old service PID disappear. Verify the event is persisted
            // in the app PID's file (not only held in the in-memory diagnostics queue).
            val diagnosticsDeadline = SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MS
            var abortConfirmedLine: String? = null
            while (SystemClock.elapsedRealtime() < diagnosticsDeadline && abortConfirmedLine == null) {
                abortConfirmedLine = runCatching {
                    appDiagnosticsFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8)
                        ?.lastOrNull { line ->
                            line.contains("[ipc_client] PROCESS_ABORT_CONFIRMED") &&
                                line.contains("Inference PID=$originalPid")
                        }
                }.getOrNull()
                if (abortConfirmedLine == null) delay(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue(
                "Surviving app-process log must persist PROCESS_ABORT_CONFIRMED for original PID=$originalPid; " +
                    "fileExists=${appDiagnosticsFile.isFile}, fileBytes=${appDiagnosticsFile.length()}, " +
                    "inMemory=${EngineDiagnostics.getLogs().lastOrNull { it.contains("PROCESS_ABORT_CONFIRMED") }}, " +
                    "fileTail=${appDiagnosticsFile.takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.takeLast(30)?.joinToString(" | ")}",
                abortConfirmedLine != null
            )

            val persistedLines = appDiagnosticsFile.readLines(Charsets.UTF_8)
            val cooperativeCancelIndex = persistedLines.indexOfFirst { line ->
                line.contains("[ipc_client] TIMEOUT_COOPERATIVE_CANCEL_SENT") &&
                    line.contains("remotePid=$originalPid")
            }
            val hardAbortIndex = persistedLines.indexOfFirst { line ->
                line.contains("[ipc_client] TIMEOUT_COOPERATIVE_CANCEL_ESCALATED") &&
                    line.contains("killing original PID=$originalPid")
            }
            assertTrue(
                "Cooperative cancellation must be sent to the original service before hard-abort escalation; " +
                    "cooperativeIndex=$cooperativeCancelIndex, hardAbortIndex=$hardAbortIndex",
                cooperativeCancelIndex >= 0 && hardAbortIndex > cooperativeCancelIndex
            )

            val recoveryDeadline = SystemClock.elapsedRealtime() + SERVICE_RECOVERY_TIMEOUT_MS
            var recoveredPid = 0
            while (SystemClock.elapsedRealtime() < recoveryDeadline) {
                val pid = client.remotePid.value
                if (pid != null && pid > 0 && pid != originalPid &&
                    pid != appPid && client.state.value == EngineState.Ready
                ) {
                    recoveredPid = pid
                    break
                }
                delay(PROCESS_POLL_INTERVAL_MS)
            }
            assertTrue("InferenceClient must bind a replacement service", recoveredPid > 0)
            assertTrue("Replacement inference process must exist", File("/proc/$recoveredPid").exists())
        } finally {
            client.close()
            directBinding?.let { binding ->
                runCatching { context.unbindService(binding.connection) }
            }
        }
    }
}
