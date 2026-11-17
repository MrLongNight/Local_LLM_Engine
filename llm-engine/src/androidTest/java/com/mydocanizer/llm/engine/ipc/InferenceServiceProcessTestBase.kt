package com.mydocanizer.llm.engine.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import com.mydocanizer.llm.engine.EngineDiagnostics
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

abstract class InferenceServiceProcessTestBase {

    protected data class BoundInferenceService(
        val service: IInferenceService,
        val connection: ServiceConnection,
        val pid: Int,
        val disconnected: CountDownLatch
    )

@Suppress("DEPRECATION")
    protected fun assertInferenceServiceManifest(context: Context) {
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

    protected fun bindInferenceService(context: Context): BoundInferenceService {
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

    protected fun resetAppProcessDiagnostics(context: Context, appPid: Int): File {
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

    protected fun awaitProcessExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            SystemClock.sleep(PROCESS_POLL_INTERVAL_MS)
        }
        return !File("/proc/$pid").exists()
    }

}

internal const val SERVICE_BIND_TIMEOUT_MS = 15_000L
internal const val PROCESS_EXIT_TIMEOUT_MS = 10_000L
internal const val SERVICE_RECOVERY_TIMEOUT_MS = 30_000L
internal const val PROCESS_POLL_INTERVAL_MS = 25L
