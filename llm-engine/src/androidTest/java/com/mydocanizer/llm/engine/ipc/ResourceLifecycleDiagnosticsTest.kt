package com.mydocanizer.llm.engine.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One end-to-end resource timeline for successful load/unload and failed-load cleanup.
 *
 * This deliberately records allocator/process measurements without asserting that PSS
 * must return to its pre-load value: native allocators and drivers may retain reusable
 * arenas after llama.cpp has released the model-owned resources.
 */
@RunWith(AndroidJUnit4::class)
class ResourceLifecycleDiagnosticsTest : InferenceServiceProcessTestBase() {

    @Test
    fun repeatedModelLifecycleCapturesCompleteBeforeAndAfterResourceSnapshots() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = File(context.cacheDir, "resource-lifecycle-tiny1m.gguf")
        val invalidModelFile = File(context.cacheDir, "resource-lifecycle-invalid.gguf")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("tiny1m.Q4_K_M.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        invalidModelFile.writeText("not a GGUF model", Charsets.UTF_8)
        assertTrue("Tiny GGUF fixture must be available", modelFile.isFile && modelFile.length() > 0L)

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
            maxPredictTokens = 8,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            microBatchSize = 16,
            timeoutConfig = timeoutConfig
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        try {
            val initialized = client.initialize()
            assertTrue("Inference service must initialize: ${initialized.exceptionOrNull()}", initialized.isSuccess)
            val servicePid = client.remotePid.value
                ?: throw AssertionError("Inference service PID was not published")
            val serviceLog = File(context.filesDir, "diagnostics/engine-$servicePid.log")

            repeat(2) { cycle ->
                val loaded = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
                assertTrue(
                    "Model load cycle ${cycle + 1} must succeed: ${loaded.exceptionOrNull()}",
                    loaded.isSuccess
                )
                awaitSnapshot(serviceLog, servicePid, "MODEL_LOADED_IDLE")

                val unloaded = client.unloadModel()
                assertTrue(
                    "Model unload cycle ${cycle + 1} must succeed: ${unloaded.exceptionOrNull()}",
                    unloaded.isSuccess
                )
                awaitSnapshot(serviceLog, servicePid, "MODEL_UNLOADED")
            }

            val failedLoad = client.loadModel(ModelSource.FilePath(invalidModelFile.absolutePath), config)
            assertTrue("Invalid GGUF must fail rather than leave a loaded model", failedLoad.isFailure)
            awaitSnapshot(serviceLog, servicePid, "MODEL_LOAD_FAILED")

            val snapshots = serviceLog.readLines().filter { it.contains("[memory] RESOURCE_SNAPSHOT - ") }
            assertTrue("At least eight lifecycle snapshots must be persisted; found ${snapshots.size}", snapshots.size >= 9)

            val expectedPhases = listOf(
                "MODEL_LOADING",
                "MODEL_LOADED_IDLE",
                "MODEL_UNLOADING",
                "MODEL_UNLOADED",
                "MODEL_LOAD_FAILED"
            )
            expectedPhases.forEach { phase ->
                assertTrue(
                    "Resource timeline must include phase=$phase",
                    snapshots.any { it.contains("phase=$phase,") }
                )
            }

            val requiredFields = listOf(
                "processPssMb=",
                "nativePssMb=",
                "nativeHeapAllocatedMb=",
                "nativeHeapFreeMb=",
                "nativeHeapSizeMb=",
                "processVmRssMb=",
                "processVmHwmMb=",
                "processThreads=",
                "openFdCount=",
                "javaHeapUsedMb=",
                "javaHeapCommittedMb=",
                "javaHeapMaxMb=",
                "systemAvailableRamMb=",
                "systemTotalRamMb="
            )
            expectedPhases.forEach { phase ->
                val snapshot = snapshots.last { it.contains("phase=$phase,") }
                requiredFields.forEach { field ->
                    assertTrue("Snapshot phase=$phase is missing $field: $snapshot", snapshot.contains(field))
                }
            }

            val fdTimeline = snapshots.mapNotNull { line ->
                val phase = Regex("phase=([^,]+)").find(line)?.groupValues?.get(1)
                val count = Regex("openFdCount=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (phase == null || count == null) null else "$phase:$count"
            }
            assertTrue(
                "Open file-descriptor counts must be numeric across lifecycle phases: $fdTimeline",
                fdTimeline.size >= 9
            )
        } finally {
            client.close()
            modelFile.delete()
            invalidModelFile.delete()
        }
    }

    private suspend fun awaitSnapshot(logFile: File, pid: Int, phase: String) {
        withTimeout(10_000L) {
            while (!logFile.isFile || logFile.readText(Charsets.UTF_8)
                    .lineSequence()
                    .none { line ->
                        line.contains("[memory] RESOURCE_SNAPSHOT - pid=$pid,phase=$phase,")
                    }
            ) {
                delay(50L)
            }
        }
    }
}
