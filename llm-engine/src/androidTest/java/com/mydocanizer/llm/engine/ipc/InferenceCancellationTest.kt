package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineDiagnosticsStorage
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
import java.util.concurrent.CompletableFuture
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
        val testStartedAtMs = SystemClock.elapsedRealtime()
        EngineDiagnostics.info(
            "ipc_test",
            "COOPERATIVE_CANCEL_TEST_STARTED",
            "appPid=$appPid,diagnosticsFile=${appDiagnosticsFile.name}"
        )
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
            EngineDiagnostics.info(
                "ipc_test",
                "COOPERATIVE_CANCEL_SERVICE_READY",
                "appPid=$appPid,remotePid=$originalPid,directPid=${directBinding.pid}," +
                    "backend=${loaded.getOrThrow().activeBackend},modelFileBytes=${modelFile.length()}," +
                    "prefillTimeoutMs=${timeoutConfig.prefillMs}"
            )

            // A long prompt with small native micro-batches leaves enough real CPU prefill
            // work after the first progress callback for Binder cancellation to reach llama.cpp.
            val longPrompt = buildString {
                repeat(160) { append("hello ") }
            }
            val observed = Collections.synchronizedList(mutableListOf<InferenceEvent>())
            val promptProgressSeen = CompletableDeferred<Pair<Int, Int>>()
            var promptProgressCheckpoint: Pair<Int, Int>? = null
            val streamJob = launch(Dispatchers.IO) {
                client.streamChat(
                    InferenceRequest.single(longPrompt, maxPredictTokens = 16)
                        .copy(timeoutConfig = timeoutConfig)
                ).collect { event ->
                    observed.add(event)
                    if (event is InferenceEvent.Progress &&
                        event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION &&
                        event.promptTokensProcessed > 0 &&
                        event.promptTokensProcessed < event.promptTokensTotal
                    ) {
                        promptProgressSeen.complete(
                            event.promptTokensProcessed to event.promptTokensTotal
                        )
                    }
                }
            }

            awaitCancellationPhase(
                context = context,
                phase = "WAIT_FOR_PROMPT_PROGRESS",
                timeoutMs = timeoutConfig.prefillMs,
                testStartedAtMs = testStartedAtMs,
                observed = observed,
                client = client,
                directBinding = directBinding,
                appDiagnosticsFile = appDiagnosticsFile
            ) {
                promptProgressCheckpoint = promptProgressSeen.await()
            }
            val progressCheckpoint = requireNotNull(promptProgressCheckpoint) {
                "Cooperative cancellation test reached its wait phase without a native prompt-progress checkpoint"
            }
            val servicePromptStartedLine = EngineDiagnosticsStorage.readAllProcessLogs(context)
                .lastOrNull { line ->
                    line.contains("[inference] PROMPT_EVALUATION_STARTED") &&
                        line.contains("pid=$originalPid")
                }
            val inferenceRunId = Regex("""runId=([^,]+)""")
                .find(servicePromptStartedLine.orEmpty())
                ?.groupValues
                ?.get(1)
                ?: throw AssertionError(
                    "Could not correlate cancellation to a service-side PROMPT_EVALUATION_STARTED event; " +
                        "serviceLine=$servicePromptStartedLine"
                )
            EngineDiagnostics.info(
                "ipc_test",
                "COOPERATIVE_CANCEL_REQUESTED",
                "appPid=$appPid,remotePid=$originalPid,eventCount=${synchronized(observed) { observed.size }}," +
                    "promptProgress=${progressCheckpoint.first}/${progressCheckpoint.second},runId=$inferenceRunId"
            )
            client.cancelInference()
            EngineDiagnostics.info(
                "ipc_test",
                "COOPERATIVE_CANCEL_CALL_RETURNED",
                "appPid=$appPid,remotePid=$originalPid,clientState=${client.state.value.javaClass.simpleName}"
            )
            awaitCancellationPhase(
                context = context,
                phase = "WAIT_FOR_STREAM_JOIN_AFTER_CANCEL",
                timeoutMs = timeoutConfig.prefillMs,
                testStartedAtMs = testStartedAtMs,
                observed = observed,
                client = client,
                directBinding = directBinding,
                appDiagnosticsFile = appDiagnosticsFile
            ) {
                streamJob.join()
            }

            val serviceCancelDeadline = SystemClock.elapsedRealtime() + 5_000L
            var prefillCancellationLine: String? = null
            while (SystemClock.elapsedRealtime() < serviceCancelDeadline && prefillCancellationLine == null) {
                prefillCancellationLine = EngineDiagnosticsStorage.readAllProcessLogs(context)
                    .lastOrNull { line ->
                        line.contains("[inference] PREFILL_CANCELLED") &&
                            line.contains("runId=$inferenceRunId,")
                    }
                if (prefillCancellationLine == null) delay(PROCESS_POLL_INTERVAL_MS)
            }
            val serviceDiagnosticsTail = EngineDiagnosticsStorage.readAllProcessLogs(context)
                .filter { it.contains("pid=$originalPid") }
                .takeLast(30)
                .joinToString(" | ")
            assertTrue(
                "CPU prefill must be interrupted by llama.cpp's abort callback (dedicated nativeResult=6), " +
                    "not converted from a different native outcome. runId=$inferenceRunId, " +
                    "actual=$prefillCancellationLine, serviceTail=$serviceDiagnosticsTail",
                prefillCancellationLine?.contains("nativeResult=6") == true
            )
            EngineDiagnostics.info(
                "ipc_test",
                "COOPERATIVE_CANCEL_NATIVE_ABORT_VERIFIED",
                "runId=$inferenceRunId,remotePid=$originalPid,promptProgress=${progressCheckpoint.first}/${progressCheckpoint.second}," +
                    "nativeResult=6"
            )

            val activeBinding = directBinding
                ?: throw AssertionError("Direct inference service binding was not established")
            awaitCancellationPhase(
                context = context,
                phase = "VERIFY_COOPERATIVE_CANCEL_AND_SAME_MODEL_REUSE",
                timeoutMs = timeoutConfig.totalInferenceMs + 5_000L,
                testStartedAtMs = testStartedAtMs,
                observed = observed,
                client = client,
                directBinding = activeBinding,
                appDiagnosticsFile = appDiagnosticsFile
            ) {
                assertTrue(
                    "CPU prefill cancellation must complete cooperatively; events=${observed.joinToString()}",
                    observed.any { it is InferenceEvent.Cancelled }
                )
                assertFalse(
                    "Cancellation must happen before prompt completion",
                    observed.any { it is InferenceEvent.Started || it is InferenceEvent.Completed }
                )
                assertEquals("Cooperative cancellation must keep the original inference PID", originalPid, client.remotePid.value)
                assertEquals("The original model must remain loaded", "MODEL_LOADED", activeBinding.service.status)
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
            }
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

    private suspend fun awaitCancellationPhase(
        context: Context,
        phase: String,
        timeoutMs: Long,
        testStartedAtMs: Long,
        observed: MutableList<InferenceEvent>,
        client: InferenceClient,
        directBinding: BoundInferenceService?,
        appDiagnosticsFile: File,
        action: suspend () -> Unit
    ) {
        val phaseStartedAtMs = SystemClock.elapsedRealtime()
        EngineDiagnostics.info(
            "ipc_test",
            "COOPERATIVE_CANCEL_PHASE_STARTED",
            "phase=$phase,timeoutMs=$timeoutMs,testElapsedMs=${phaseStartedAtMs - testStartedAtMs}"
        )
        try {
            withTimeout(timeoutMs) { action() }
            val elapsedMs = SystemClock.elapsedRealtime() - phaseStartedAtMs
            EngineDiagnostics.info(
                "ipc_test",
                "COOPERATIVE_CANCEL_PHASE_COMPLETED",
                "phase=$phase,elapsedMs=$elapsedMs,testElapsedMs=${SystemClock.elapsedRealtime() - testStartedAtMs}"
            )
        } catch (failure: Throwable) {
            val phaseElapsedMs = SystemClock.elapsedRealtime() - phaseStartedAtMs
            val binderStatus = directBinding?.let { binding ->
                val query = CompletableFuture.supplyAsync {
                    runCatching { binding.service.status }.fold(
                        onSuccess = { status -> "OK:$status" },
                        onFailure = { error ->
                            "ERROR:${error.javaClass.simpleName}:${error.message ?: "no_message"}"
                        }
                    )
                }
                runCatching { query.get(1_000L, TimeUnit.MILLISECONDS) }
                    .getOrElse { error ->
                        "QUERY_FAILED:${error.javaClass.simpleName}:${error.message ?: "no_message"}"
                    }
            } ?: "NOT_BOUND"
            val eventSequence = synchronized(observed) {
                observed.mapIndexed { index, event -> "$index:${summarizeEvent(event)}" }.takeLast(80)
            }
            val diagnosticsDirectory = File(context.filesDir, "diagnostics")
            val processLogTail = diagnosticsDirectory.listFiles { file ->
                file.isFile && file.name.startsWith("engine-") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }?.take(4)?.joinToString("\n") { file ->
                val tail = runCatching {
                    file.readLines(Charsets.UTF_8).takeLast(60).joinToString("\n")
                }.getOrElse { error -> "<read failed: ${error.javaClass.simpleName}>" }
                "FILE=${file.name},bytes=${file.length()}\n$tail"
            } ?: "<diagnostics directory unavailable>"
            val snapshot = """
                phase=$phase
                failure=${failure.javaClass.name}:${failure.message}
                phaseElapsedMs=$phaseElapsedMs
                testElapsedMs=${SystemClock.elapsedRealtime() - testStartedAtMs}
                appPid=${Process.myPid()}
                clientState=${client.state.value.javaClass.simpleName}
                remotePid=${client.remotePid.value}
                currentModelPath=${client.currentModel.value?.path ?: "none"}
                directServicePid=${directBinding?.pid ?: "none"}
                directServiceDisconnected=${directBinding?.disconnected?.count == 0L}
                directBinderStatus=$binderStatus
                appDiagnosticsFile=${appDiagnosticsFile.name},exists=${appDiagnosticsFile.isFile},bytes=${appDiagnosticsFile.length()}
                observedEvents=${eventSequence.size}
                eventSequence:
                ${eventSequence.joinToString("\n")}
                processDiagnosticsTail:
                $processLogTail
            """.trimIndent().take(20_000)
            EngineDiagnostics.error("ipc_test", "COOPERATIVE_CANCEL_PHASE_FAILED", failure)
            EngineDiagnostics.info("ipc_test", "COOPERATIVE_CANCEL_FAILURE_SNAPSHOT", snapshot)
            val assertion = AssertionError(
                "Cooperative cancellation phase=$phase failed after ${phaseElapsedMs}ms; " +
                    "see COOPERATIVE_CANCEL_FAILURE_SNAPSHOT in app diagnostics.\n$snapshot"
            )
            assertion.initCause(failure)
            throw assertion
        }
    }

    private fun summarizeEvent(event: InferenceEvent): String = when (event) {
        is InferenceEvent.Started -> "Started(promptMessages=${event.promptMessageCount})"
        is InferenceEvent.Token -> "Token(index=${event.tokenIndex},chars=${event.token.length})"
        is InferenceEvent.Progress ->
            "Progress(phase=${event.phase},prompt=${event.promptTokensProcessed}/${event.promptTokensTotal}," +
                "generated=${event.tokensGenerated},elapsedMs=${event.elapsedMs})"
        is InferenceEvent.Completed ->
            "Completed(tokens=${event.result.tokensGenerated},finish=${event.result.finishReason},chars=${event.result.text.length})"
        is InferenceEvent.Cancelled ->
            "Cancelled(tokens=${event.tokensGenerated},partialChars=${event.partialText.length})"
        is InferenceEvent.Error ->
            "Error(type=${event.throwable.javaClass.simpleName},message=${event.throwable.message?.take(160)}," +
                "partialChars=${event.partialText.length})"
    }
}
