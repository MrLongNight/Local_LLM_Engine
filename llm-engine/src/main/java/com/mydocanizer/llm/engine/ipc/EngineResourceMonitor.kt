package com.mydocanizer.llm.engine.ipc

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.internal.NativeEngineHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Samples memory from the dedicated :inference process, where llama.cpp and Vulkan
 * allocations live. PSS includes native allocations and mapped graphics memory when
 * Android accounts for them; Vulkan device-local allocations are not guaranteed to be
 * represented in process PSS and must be read from the Vulkan backend diagnostics.
 */
internal class EngineResourceMonitor(context: Context) : AutoCloseable {

    private data class ModelSnapshot(
        val name: String,
        val fileBytes: Long,
        val contextTokens: Int,
        val batchSize: Int,
        val threads: Int,
        val batchThreads: Int,
        val microBatchSize: Int,
        val gpuLayers: Int,
        val backendPreference: String,
        val activeBackend: String
    )

    companion object {
        private const val IDLE_INTERVAL_MS = 5_000L
        private const val ACTIVE_INTERVAL_MS = 1_000L
        private const val NATIVE_BACKEND_MEMORY_INTERVAL_NS = 5_000_000_000L
        private const val MIB = 1024.0 * 1024.0
    }

    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phase = AtomicReference("SERVICE_STARTING")
    private val model = AtomicReference<ModelSnapshot?>(null)
    private val sampleErrorLogged = AtomicBoolean(false)
    private val nativeBackendReady = AtomicBoolean(false)
    private val nativeMemoryLock = Any()
    private val lastNativeBackendMemorySampleNs = AtomicLong(0L)
    private var samplingJob: Job? = null

    @Synchronized
    fun start() {
        if (samplingJob?.isActive == true) return
        samplingJob = scope.launch {
            while (isActive) {
                sample()
                val currentPhase = phase.get()
                delay(if (currentPhase in ACTIVE_PHASES || currentPhase.startsWith("INFERENCE_")) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS)
            }
        }
    }

    fun beginModelLoad(path: String, config: EngineConfig) {
        val file = File(path)
        model.set(
            ModelSnapshot(
                name = file.name,
                fileBytes = runCatching { file.length() }.getOrDefault(0L),
                contextTokens = config.contextSize,
                batchSize = config.batchSize,
                threads = config.threads,
                batchThreads = config.effectiveBatchThreads,
                microBatchSize = config.effectiveMicroBatchSize,
                gpuLayers = config.gpuLayers,
                backendPreference = config.backendPreference.name,
                activeBackend = "loading"
            )
        )
        synchronized(nativeMemoryLock) { phase.set("MODEL_LOADING") }
        logPhase("MODEL_LOADING")
    }

    fun modelLoaded(info: ModelInfo, config: EngineConfig) {
        model.set(
            ModelSnapshot(
                name = info.name,
                fileBytes = info.sizeBytes,
                contextTokens = config.contextSize,
                batchSize = config.batchSize,
                threads = config.threads,
                batchThreads = config.effectiveBatchThreads,
                microBatchSize = config.effectiveMicroBatchSize,
                gpuLayers = config.gpuLayers,
                backendPreference = config.backendPreference.name,
                activeBackend = info.activeBackend
            )
        )
        synchronized(nativeMemoryLock) { phase.set("MODEL_LOADED_IDLE") }
        logPhase("MODEL_LOADED_IDLE")
    }

    fun nativeBackendReady() {
        synchronized(nativeMemoryLock) { nativeBackendReady.set(true) }
    }

    fun modelUnloading() {
        synchronized(nativeMemoryLock) { phase.set("MODEL_UNLOADING") }
        logPhase("MODEL_UNLOADING")
    }

    fun modelLoadFailed() {
        synchronized(nativeMemoryLock) { phase.set("MODEL_LOAD_FAILED") }
        logPhase("MODEL_LOAD_FAILED")
    }

    fun inferenceStarted(runId: String) {
        val runPhase = "INFERENCE_${runId.take(48)}"
        synchronized(nativeMemoryLock) { phase.set(runPhase) }
        logPhase(runPhase)
        logRunEnvironment("INFERENCE_START:${runId.take(48)}")
        sample()
    }

    private fun logRunEnvironment(event: String) {
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else -1
        EngineDiagnostics.info(
            "memory", "RUN_ENVIRONMENT",
            "event=$event,pid=${Process.myPid()},availMemMb=${if (activityManager != null) mb(memory.availMem) else "unknown"}," +
                "lowMemory=${if (activityManager != null) memory.lowMemory else "unknown"}," +
                "thermalStatus=${thermalName(thermal)},thermalStatusCode=$thermal"
        )
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNAVAILABLE"
    }

    fun inferenceFinished(runId: String) {
        // Capture terminal PSS while the run-specific phase is still active.
        if (phase.get() == "INFERENCE_${runId.take(48)}") sample()
        synchronized(nativeMemoryLock) {
            if (phase.get() == "INFERENCE_${runId.take(48)}") {
                phase.set(if (model.get() == null) "SERVICE_IDLE" else "MODEL_LOADED_IDLE")
            }
        }
        logPhase(phase.get())
    }

    fun modelUnloaded() {
        model.set(null)
        synchronized(nativeMemoryLock) { phase.set("MODEL_UNLOADED") }
        logPhase("MODEL_UNLOADED")
    }

    fun modelUnloadFailed() {
        synchronized(nativeMemoryLock) {
            phase.set(if (model.get() == null) "SERVICE_IDLE" else "MODEL_LOADED_IDLE")
        }
        logPhase("MODEL_UNLOAD_FAILED")
    }

    private fun logPhase(value: String) {
        EngineDiagnostics.info("memory", "RESOURCE_PHASE", "pid=${Process.myPid()},phase=$value")
    }

    private fun sample() {
        try {
            val processMemory = Debug.MemoryInfo()
            Debug.getMemoryInfo(processMemory)

            val systemMemory = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(systemMemory)

            val runtime = Runtime.getRuntime()
            val javaUsedBytes = runtime.totalMemory() - runtime.freeMemory()
            val snapshot = model.get()

            val nativeBackendMemory = synchronized(nativeMemoryLock) {
                val currentPhase = phase.get()
                val nowNs = System.nanoTime()
                val lastNativeSampleNs = lastNativeBackendMemorySampleNs.get()
                val shouldSample = nativeBackendReady.get() &&
                    currentPhase !in ACTIVE_PHASES && !currentPhase.startsWith("INFERENCE_") &&
                    nowNs - lastNativeSampleNs >= NATIVE_BACKEND_MEMORY_INTERVAL_NS &&
                    lastNativeBackendMemorySampleNs.compareAndSet(lastNativeSampleNs, nowNs)
                // Vulkan memory-budget queries enumerate physical-device memory heaps. Keep these
                // out of inference/load and less frequent than Android PSS/heap samples.
                if (shouldSample) {
                    runCatching { NativeEngineHandle.nativeGetBackendDiagnostics() }.getOrNull()
                } else null
            }

            val graphicsPssKb = runCatching {
                processMemory.getMemoryStats()["summary.graphics"]?.toLongOrNull()
            }.getOrNull()

            val details = buildString {
                append("pid=${Process.myPid()}")
                append(",phase=${phase.get()}")
                append(",processPssMb=${mb(processMemory.totalPss * 1024L)}")
                append(",nativePssMb=${mb(processMemory.nativePss * 1024L)}")
                append(",dalvikPssMb=${mb(processMemory.dalvikPss * 1024L)}")
                append(",otherPssMb=${mb(processMemory.otherPss * 1024L)}")
                graphicsPssKb?.let { append(",graphicsPssMb=${mb(it * 1024L)}") }
                append(",nativeHeapAllocatedMb=${mb(Debug.getNativeHeapAllocatedSize())}")
                append(",javaHeapUsedMb=${mb(javaUsedBytes)}")
                append(",javaHeapCommittedMb=${mb(runtime.totalMemory())}")
                append(",javaHeapMaxMb=${mb(runtime.maxMemory())}")
                val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                } else -1
                append(",thermalStatus=${thermalName(thermalStatus)}")
                append(",thermalStatusCode=$thermalStatus")
                if (activityManager != null) {
                    append(",systemAvailableRamMb=${mb(systemMemory.availMem)}")
                    append(",systemTotalRamMb=${mb(systemMemory.totalMem)}")
                    append(",systemLowMemoryThresholdMb=${mb(systemMemory.threshold)}")
                    append(",systemLowMemory=${systemMemory.lowMemory}")
                }
                nativeBackendMemory?.let { append(",backendMemory={$it}") }
                if (snapshot != null) {
                    append(",model=${snapshot.name}")
                    append(",modelFileMb=${mb(snapshot.fileBytes)}")
                    append(",contextTokens=${snapshot.contextTokens}")
                    append(",batchSize=${snapshot.batchSize}")
                    append(",threads=${snapshot.threads}")
                    append(",batchThreads=${snapshot.batchThreads}")
                    append(",microBatchSize=${snapshot.microBatchSize}")
                    append(",gpuLayersRequested=${snapshot.gpuLayers}")
                    append(",backendPreference=${snapshot.backendPreference}")
                    append(",backendActive=${snapshot.activeBackend}")
                }
            }

            sampleErrorLogged.set(false)
            EngineDiagnostics.info("memory", "RESOURCE_SNAPSHOT", details)
        } catch (error: Throwable) {
            if (sampleErrorLogged.compareAndSet(false, true)) {
                EngineDiagnostics.error("memory", "RESOURCE_SAMPLE_FAILED", error)
            }
        }
    }

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0L) / MIB)

    @Synchronized
    override fun close() {
        synchronized(nativeMemoryLock) { nativeBackendReady.set(false) }
        samplingJob?.cancel()
        samplingJob = null
        scope.cancel()
    }

    private val ACTIVE_PHASES = setOf("MODEL_LOADING", "INFERENCE", "MODEL_UNLOADING")
}
