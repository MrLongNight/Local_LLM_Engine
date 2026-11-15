package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceTimeoutException
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
class InferenceCancellationTest : InferenceServiceProcessTestBase() {

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
            prefillMs = 1_200L,
            firstTokenMs = 2_500L,
            interTokenMs = 1_500L,
            totalInferenceMs = 6_000L,
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
