package com.mydocanizer.llm.engine.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
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
            val initialLineCount = if (serviceLog.isFile) serviceLog.readLines().size else 0
            var lastObservedLineCount = initialLineCount

            repeat(2) { cycle ->
                val loaded = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
                assertTrue(
                    "Model load cycle ${cycle + 1} must succeed: ${loaded.exceptionOrNull()}",
                    loaded.isSuccess
                )
                lastObservedLineCount = awaitSnapshot(
                    serviceLog, servicePid, "MODEL_LOADED_IDLE", lastObservedLineCount
                )

                val unloaded = client.unloadModel()
                assertTrue(
                    "Model unload cycle ${cycle + 1} must succeed: ${unloaded.exceptionOrNull()}",
                    unloaded.isSuccess
                )
                lastObservedLineCount = awaitSnapshot(
                    serviceLog, servicePid, "MODEL_UNLOADED", lastObservedLineCount
                )
            }

            val failedLoad = client.loadModel(ModelSource.FilePath(invalidModelFile.absolutePath), config)
            assertTrue("Invalid GGUF must fail rather than leave a loaded model", failedLoad.isFailure)
            lastObservedLineCount = awaitSnapshot(
                serviceLog, servicePid, "MODEL_LOAD_FAILED", lastObservedLineCount
            )

            val testLines = serviceLog.readLines().drop(initialLineCount)
            val snapshots = testLines.filter { it.contains("[memory] RESOURCE_SNAPSHOT - ") }
            assertTrue("At least nine lifecycle snapshots must be persisted; found ${snapshots.size}", snapshots.size >= 9)

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

            val nativeStateLines = testLines
                .filter { it.contains("[native] NATIVE_MODEL_RESOURCE_STATE - ") }
            val loadedNativeState = nativeStateLines.firstOrNull {
                it.contains("stage=AFTER_LOAD_ATTEMPT,loadResult=0,")
            } ?: throw AssertionError("Native state after successful load was not recorded")
            listOf("modelPresent=1", "contextPresent=1", "batchAllocated=1", "samplerPresent=1")
                .forEach { field ->
                    assertTrue("Successful native load must own $field: $loadedNativeState", loadedNativeState.contains(field))
                }

            val unloadedNativeState = nativeStateLines.lastOrNull {
                it.contains("stage=AFTER_UNLOAD,")
            } ?: throw AssertionError("Native state after model unload was not recorded")
            listOf(
                "modelPresent=0",
                "contextPresent=0",
                "batchAllocated=0",
                "samplerPresent=0",
                "backendSamplerAttached=0",
                "modelFileStreamOpen=0",
                "cachedTokens=0",
                "chatMessages=0",
                "activeBackend=None"
            ).forEach { field ->
                assertTrue("Native unload must release $field: $unloadedNativeState", unloadedNativeState.contains(field))
            }

            val failedNativeState = nativeStateLines.lastOrNull {
                it.contains("stage=AFTER_LOAD_ATTEMPT,") && !it.contains("loadResult=0,")
            } ?: throw AssertionError("Native state after failed model load was not recorded")
            listOf("modelPresent=0", "contextPresent=0", "batchAllocated=0", "samplerPresent=0", "modelFileStreamOpen=0")
                .forEach { field ->
                    assertTrue("Failed native load must clean up $field: $failedNativeState", failedNativeState.contains(field))
                }

            val report = EngineDiagnostics.buildCompactReportForTest(testLines)
            listOf("nativeHeapFree=", "nativeHeapSize=", "VmRSS=", "VmHWM=", "openFDs=", "threads=")
                .forEach { field ->
                    assertTrue("Exported memory timeline must retain $field", report.contains(field))
                }
            assertTrue("Exported report must retain native resource ownership evidence", report.contains("NATIVE_MODEL_RESOURCE_STATE"))
            assertTrue("Exported report must retain released model state", report.contains("modelPresent=0"))
        } finally {
            client.close()
            modelFile.delete()
            invalidModelFile.delete()
        }
    }

    private suspend fun awaitSnapshot(
        logFile: File,
        pid: Int,
        phase: String,
        afterLineCount: Int
    ): Int = withTimeout(10_000L) {
        var observedLineCount: Int? = null
        while (observedLineCount == null) {
            val lines = if (logFile.isFile) logFile.readLines(Charsets.UTF_8) else emptyList()
            if (lines.drop(afterLineCount).any { line ->
                    line.contains("[memory] RESOURCE_SNAPSHOT - pid=$pid,phase=$phase,")
                }
            ) {
                observedLineCount = lines.size
            } else {
                delay(50L)
            }
        }
        observedLineCount
    }
}
