package com.mydocanizer.llm.engine.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceTimeoutException
import com.mydocanizer.llm.engine.TimeoutConfig
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.internal.ModelSourceResolver
import com.mydocanizer.llm.engine.internal.NativeEngineHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList

@RunWith(AndroidJUnit4::class)
class InferenceServiceProcessTest {

    private data class BoundInferenceService(
        val service: IInferenceService,
        val connection: ServiceConnection,
        val pid: Int,
        val disconnected: CountDownLatch
    )









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

    @Suppress("DEPRECATION")
    private fun assertInferenceServiceManifest(context: Context) {
        val component = ComponentName(context, InferenceService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)

        assertEquals(
            "Service must be registered in the app-private :inference process",
            "${context.packageName}:inference",
            serviceInfo.processName
        )
        assertFalse("Inference service must not be exported", serviceInfo.exported)
        assertFalse(
            "Inference service must not request Android's isolated UID sandbox",
            (serviceInfo.flags and ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
        )
    }

    private fun bindInferenceService(context: Context): BoundInferenceService {
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val serviceRef = AtomicReference<IInferenceService?>()

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceRef.set(IInferenceService.Stub.asInterface(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                disconnected.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                disconnected.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.countDown()
                disconnected.countDown()
            }
        }

        val intent = Intent(context, InferenceService::class.java).apply {
            action = InferenceService.ACTION_BIND_INFERENCE
        }
        assertTrue(
            "Android must accept the inference service binding",
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        )
        assertTrue(
            "Inference service must connect",
            connected.await(SERVICE_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )

        val service = serviceRef.get()
            ?: throw AssertionError("Inference service connected without an AIDL interface")
        return BoundInferenceService(
            service = service,
            connection = connection,
            pid = service.processId,
            disconnected = disconnected
        )
    }

    private fun resetAppProcessDiagnostics(context: Context, appPid: Int): File {
        val directory = File(context.applicationContext.filesDir, "diagnostics")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create app-process diagnostics directory: ${directory.absolutePath}"
        }
        val logFile = File(directory, "engine-$appPid.log")
        if (logFile.exists()) {
            check(logFile.delete()) { "Could not clear stale app-process log: ${logFile.absolutePath}" }
        }
        EngineDiagnostics.initialize(context.applicationContext)
        EngineDiagnostics.info("ipc_test", "APP_PROCESS_LOG_PERSISTENCE_PROBE", "appPid=$appPid")
        check(
            logFile.isFile &&
                logFile.readText(Charsets.UTF_8).contains("[ipc_test] APP_PROCESS_LOG_PERSISTENCE_PROBE")
        ) {
            "EngineDiagnostics did not persist the initial app-process probe to ${logFile.absolutePath}"
        }
        return logFile
    }

    private fun awaitProcessExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
        }
        return !File("/proc/$pid").exists()
    }

    private companion object {
        const val SERVICE_BIND_TIMEOUT_MS = 15_000L
        const val PROCESS_EXIT_TIMEOUT_MS = 10_000L
        const val SERVICE_RECOVERY_TIMEOUT_MS = 30_000L
        const val PROCESS_POLL_INTERVAL_MS = 25L
    }
}
