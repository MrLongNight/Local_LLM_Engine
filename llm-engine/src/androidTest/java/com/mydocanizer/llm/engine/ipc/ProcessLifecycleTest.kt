package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.EngineState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
