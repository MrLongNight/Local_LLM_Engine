package com.mydocanizer.llm.engine.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class InferenceServiceProcessTest {

    private data class BoundInferenceService(
        val service: IInferenceService,
        val connection: ServiceConnection,
        val pid: Int,
        val disconnected: CountDownLatch
    )

    @Test
    fun serviceRunsInDedicatedProcessCanBeKilledAndBoundAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        const val PROCESS_POLL_INTERVAL_MS = 25L
    }
}
