package com.mydocanizer.llm.engine.ipc

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.content.ComponentCallbacks2
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.BuildConfig
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceEvent.ProgressPhase
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceResult
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.internal.LocalLlmEngineImpl
import com.mydocanizer.llm.engine.internal.ModelMemoryGuard
import com.mydocanizer.llm.engine.internal.InsufficientModelMemoryException
import com.mydocanizer.llm.engine.internal.InsufficientAvailableModelMemoryException
import com.mydocanizer.llm.engine.internal.ModelMemorySettlingTimeoutException
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android Service running in a dedicated app-private process (:inference) under the app UID.
 *
 * Hosts the native llama.cpp execution engine and exposes an AIDL-based IPC interface
 * to protect the main application process from native driver faults, Out-Of-Memory (OOM)
 * terminations, and C++ crashes.
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        const val ACTION_BIND_INFERENCE = "com.mydocanizer.llm.engine.ipc.BIND_INFERENCE"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InferenceServiceWorkerThread").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(workerDispatcher + SupervisorJob())

    @Volatile
    private var engine: LocalLlmEngine? = null
    private val activeRequestId = AtomicReference<String?>(null)
    @Volatile
    private var activeInferenceJob: Job? = null
    // Binder threads may call streamChat concurrently. The check of the active slot and the
    // assignment of the new Job must be one atomic step.
    private val inferenceSlotLock = Any()
    private val isShuttingDown = AtomicBoolean(false)
    private val memoryTrimUnloadPending = AtomicBoolean(false)
    private var resourceMonitor: EngineResourceMonitor? = null
    @Volatile
    private var activeModelDescriptor: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        EngineDiagnostics.initialize(applicationContext)
        resourceMonitor = EngineResourceMonitor(applicationContext).also { it.start() }
        val pid = Process.myPid()
        EngineDiagnostics.info("ipc", "SERVICE_CREATED", "InferenceService started in process PID=$pid")
        Log.i(TAG, "InferenceService created in process PID=$pid")

        serviceScope.launch {
            initEngine()
        }
    }

    private fun initEngine(): LocalLlmEngine {
        val current = engine
        if (current != null) return current
        EngineDiagnostics.info(
            "ipc",
            "LOCAL_ENGINE_CREATE",
            "Creating in-process native engine inside :inference PID=${Process.myPid()}"
        )
        val newEngine = LocalLlmEngineImpl(applicationContext)
        engine = newEngine
        return newEngine
    }

    /**
     * Binder entry points run outside the single-thread worker. Cancel native inference
     * here, before enqueueing model load/unload, otherwise the queued operation cannot
     * reach LocalLlmEngineImpl while its blocking JNI call owns the worker.
     */
    private fun interruptInferenceForModelOperation(operation: String) {
        if (activeInferenceJob?.isActive != true) return
        EngineDiagnostics.info(
            "ipc",
            "INFERENCE_PREEMPTED_FOR_MODEL_OPERATION",
            "operation=$operation, requestId=${activeRequestId.get()}"
        )
        // Do not cancel the collecting Job here. The native engine emits Cancelled with
        // its partial output after observing the signal; cancelling the Job first loses it.
        engine?.cancelInference()
    }

    override fun onBind(intent: Intent?): IBinder {
        EngineDiagnostics.info("ipc", "SERVICE_BOUND", "Client bound to InferenceService (PID=${Process.myPid()})")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        EngineDiagnostics.info("ipc", "SERVICE_UNBOUND", "Client unbound from InferenceService")
        return super.onUnbind(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EngineDiagnostics.info("memory", "SERVICE_TRIM_MEMORY", "level=$level,pid=${Process.myPid()}")
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            !memoryTrimUnloadPending.compareAndSet(false, true)
        ) return

        val localEngine = engine
        if (localEngine == null) {
            memoryTrimUnloadPending.set(false)
            return
        }
        val activeJob = activeInferenceJob
        if (activeJob?.isActive == true) {
            EngineDiagnostics.info("memory", "MODEL_UNLOAD_REQUESTED_FOR_TRIM", "reason=active_inference,level=$level")
            localEngine.cancelInference()
            activeJob.invokeOnCompletion {
                serviceScope.launch {
                    try {
                        unloadModelForMemoryPressure(localEngine, level)
                    } finally {
                        memoryTrimUnloadPending.set(false)
                    }
                }
            }
        } else {
            serviceScope.launch {
                try {
                    unloadModelForMemoryPressure(localEngine, level)
                } finally {
                    memoryTrimUnloadPending.set(false)
                }
            }
        }
    }

    private suspend fun unloadModelForMemoryPressure(localEngine: LocalLlmEngine, level: Int) {
        if (engine !== localEngine || activeInferenceJob?.isActive == true) return
        val currentModel = localEngine.currentModel.value ?: return
        EngineDiagnostics.info(
            "memory",
            "MODEL_UNLOAD_FOR_MEMORY_PRESSURE",
            "level=$level,model=${currentModel.name}"
        )
        resourceMonitor?.modelUnloading()
        localEngine.unloadModel().onSuccess {
            activeModelDescriptor?.close()
            activeModelDescriptor = null
            resourceMonitor?.modelUnloaded()
            EngineDiagnostics.info("memory", "MODEL_UNLOADED_FOR_MEMORY_PRESSURE", "level=$level")
        }.onFailure { error ->
            resourceMonitor?.modelUnloadFailed()
            EngineDiagnostics.error("memory", "MODEL_UNLOAD_FOR_MEMORY_PRESSURE_FAILED", error)
        }
    }

    override fun onDestroy() {
        isShuttingDown.set(true)
        resourceMonitor?.close()
        resourceMonitor = null
        EngineDiagnostics.info("ipc", "SERVICE_DESTROYED", "InferenceService destroying")

        // Signal native cancellation before waiting for the serial worker. Cleanup must not
        // be launched into serviceScope and then immediately cancelled with that same scope.
        activeInferenceJob?.cancel()
        engine?.cancelInference()
        runCatching {
            runBlocking(workerDispatcher) {
                activeInferenceJob?.join()
                engine?.close()
                engine = null
                activeModelDescriptor?.close()
                activeModelDescriptor = null
            }
        }.onFailure { error ->
            Log.e(TAG, "Error closing inference engine on destroy", error)
            EngineDiagnostics.error("ipc", "SERVICE_ENGINE_CLEANUP_FAILED", error)
        }

        workerExecutor.shutdown()
        serviceScope.cancel()
        workerDispatcher.close()
        super.onDestroy()
    }


    private data class ModelMemoryAdmission(
        val memoryInfo: ActivityManager.MemoryInfo,
        val plan: ModelMemoryGuard.Plan
    )

    /**
     * After a Vulkan model is unloaded, Mali UMA allocations and reclaimable GGUF pages
     * may take time to settle. A CPU load is admitted only after the estimate fits and
     * three consecutive available-RAM samples are stable. Failure is reported as a
     * controlled memory rejection; native CPU allocation is never attempted.
     */
    private suspend fun awaitStableCpuMemoryAfterVulkanUnload(
        memoryManager: ActivityManager,
        modelBytes: Long,
        config: EngineConfig,
        previousBackend: String
    ): ModelMemoryAdmission {
        val startedAtNanos = System.nanoTime()
        val samples = mutableListOf<Long>()
        var lastMemoryInfo: ActivityManager.MemoryInfo? = null
        var lastPlan: ModelMemoryGuard.Plan? = null
        var lastMemoryRejection: InsufficientAvailableModelMemoryException? = null
        var attempt = 0

        while (true) {
            attempt++
            val memoryInfo = ActivityManager.MemoryInfo()
            memoryManager.getMemoryInfo(memoryInfo)
            lastMemoryInfo = memoryInfo
            samples += memoryInfo.availMem
            if (samples.size > ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_COUNT) {
                samples.removeAt(0)
            }

            val plan = try {
                ModelMemoryGuard.plan(
                    modelBytes = modelBytes,
                    requestedContextTokens = config.contextSize,
                    availableBytes = memoryInfo.availMem,
                    totalBytes = memoryInfo.totalMem,
                    lowMemory = memoryInfo.lowMemory,
                    allowHardLimitOverride = config.memoryGuardOverride,
                    requireAvailableMemory = true
                ).also {
                    lastPlan = it
                    lastMemoryRejection = null
                }
            } catch (rejected: InsufficientAvailableModelMemoryException) {
                lastMemoryRejection = rejected
                null
            }

            val stable = ModelMemoryGuard.hasStableAvailableMemory(samples)
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            val windowRangeBytes = if (samples.size == ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_COUNT) {
                (samples.maxOrNull() ?: 0L) - (samples.minOrNull() ?: 0L)
            } else {
                null
            }
            resourceMonitor?.sampleModelUnloadSettling()

            EngineDiagnostics.info(
                "memory",
                "MODEL_UNLOAD_SETTLING_SAMPLE",
                "previousBackend=${previousBackend},requestedBackend=CPU,attempt=$attempt," +
                    "elapsedMs=$elapsedMs,availableBytes=${memoryInfo.availMem}," +
                    "requiredBytes=${plan?.requiredBytes ?: lastMemoryRejection?.requiredBytes ?: -1L}," +
                    "lowMemory=${memoryInfo.lowMemory},stable=$stable," +
                    "stableSampleCount=${samples.size},stableRangeBytes=${windowRangeBytes ?: -1L}"
            )

            if (plan != null && !memoryInfo.lowMemory && stable) {
                EngineDiagnostics.info(
                    "memory",
                    "MODEL_UNLOAD_SETTLING_COMPLETED",
                    "previousBackend=${previousBackend},attempt=$attempt,elapsedMs=$elapsedMs," +
                        "availableBytes=${memoryInfo.availMem},requiredBytes=${plan.requiredBytes}," +
                        "stableSampleCount=${samples.size},stableRangeBytes=${windowRangeBytes ?: -1L}"
                )
                return ModelMemoryAdmission(memoryInfo, plan)
            }

            if (elapsedMs >= ModelMemoryGuard.CPU_MEMORY_SETTLING_MAX_WAIT_MS) break
            delay(ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_INTERVAL_MS)
        }

        val memoryInfo = lastMemoryInfo
            ?: throw IllegalStateException("CPU memory-settling window produced no system-memory sample")
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
        val windowRangeBytes = if (samples.size == ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_COUNT) {
            (samples.maxOrNull() ?: 0L) - (samples.minOrNull() ?: 0L)
        } else {
            null
        }

        EngineDiagnostics.info(
            "memory",
            "MODEL_UNLOAD_SETTLING_FAILED",
            "previousBackend=${previousBackend},elapsedMs=$elapsedMs," +
                "availableBytes=${memoryInfo.availMem},lowMemory=${memoryInfo.lowMemory}," +
                "stableSampleCount=${samples.size},stableRangeBytes=${windowRangeBytes ?: -1L}," +
                "reason=${lastMemoryRejection?.diagnosticDetails() ?: "memory_not_stable"}"
        )

        lastMemoryRejection?.let { throw it }
        val plan = lastPlan
            ?: throw IllegalStateException("CPU memory-settling window ended without a memory plan")
        throw ModelMemorySettlingTimeoutException(
            previousBackend = previousBackend,
            lastAvailableBytes = memoryInfo.availMem,
            requiredBytes = plan.requiredBytes,
            elapsedMs = elapsedMs,
            lowMemory = memoryInfo.lowMemory,
            stableSampleCount = samples.size,
            stableRangeBytes = windowRangeBytes
        )
    }

    private val binder = object : IInferenceService.Stub() {

        override fun initialize() {
            serviceScope.launch {
                try {
                    val e = initEngine()
                    val initialization = e.initialize()
                    if (initialization.isSuccess) resourceMonitor?.nativeBackendReady()
                } catch (e: Exception) {
                    EngineDiagnostics.error("ipc", "INIT_FAILED", e)
                }
            }
        }

        override fun loadModel(
            modelPath: String,
            modelDescriptor: ParcelFileDescriptor,
            displayName: String,
            config: EngineConfig,
            callback: IInferenceCallback?
        ) {
            interruptInferenceForModelOperation("loadModel")
            serviceScope.launch {
                var progressJob: Job? = null
                var modelLoadedSuccessfully = false
                var descriptorTransferred = false
                var modelLoadStarted = false
                var previousModelUnloaded = false
                try {
                    val e = initEngine()
                    val previousModel = e.currentModel.value
                    val previousDescriptor = activeModelDescriptor
                    val previousBackend = previousModel?.activeBackend
                    val previousModelPresent = previousModel != null || previousDescriptor != null

                    // For a fresh load, begin the resource timeline immediately. During a
                    // replacement, keep the old model identity until its native unload and
                    // descriptor close have both completed.
                    if (!previousModelPresent) {
                        resourceMonitor?.beginModelLoad(modelPath, config)
                        modelLoadStarted = true
                    }

                    val initialization = e.initialize()
                    if (initialization.isFailure) {
                        val error = initialization.exceptionOrNull()
                            ?: IllegalStateException("Native engine initialization failed")
                        EngineDiagnostics.error("ipc", "ENGINE_INITIALIZATION_FAILED", error)
                        callback?.onModelLoadFailed(error.message ?: "Native engine initialization failed")
                        return@launch
                    }
                    resourceMonitor?.nativeBackendReady()
                    val nativePath = "/proc/self/fd/${modelDescriptor.fd}"
                    EngineDiagnostics.info(
                        "ipc",
                        "LOAD_MODEL_REQUESTED",
                        "source=$modelPath,nativePath=$nativePath,directDescriptor=true,ctx=${config.contextSize}"
                    )
                    progressJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        e.modelLoadProgressEvents.collect { percent ->
                            runCatching { callback?.onModelLoadProgress(percent.coerceIn(0, 99)) }
                        }
                    }

                    if (previousModelPresent) {
                        resourceMonitor?.modelUnloading()
                        EngineDiagnostics.info(
                            "ipc",
                            "MODEL_RELOAD_UNLOAD_STARTED",
                            "previousBackend=${previousBackend ?: "unknown"},previousModel=${previousModel?.name ?: "descriptor_only"}"
                        )
                        try {
                            e.unloadModel().getOrThrow()
                            activeModelDescriptor = null
                            previousDescriptor?.close()
                        } catch (unloadError: Throwable) {
                            resourceMonitor?.modelUnloadFailed()
                            throw unloadError
                        }
                        resourceMonitor?.modelUnloaded()
                        previousModelUnloaded = true
                        EngineDiagnostics.info(
                            "ipc",
                            "MODEL_RELOAD_UNLOAD_COMPLETED",
                            "previousBackend=${previousBackend ?: "unknown"},descriptorClosed=${previousDescriptor != null}"
                        )
                    }

                    val memoryManager = getSystemService(ActivityManager::class.java)
                        ?: throw IllegalStateException(
                            "Android ActivityManager is unavailable; cannot safely estimate model-load memory"
                        )
                    val descriptorSize = modelDescriptor.statSize
                    val modelBytes = when {
                        descriptorSize > 0L -> descriptorSize
                        modelPath.startsWith("saf-direct:") -> throw IllegalArgumentException(
                            "Der SAF-Anbieter stellt keinen regulären Deskriptor mit bekannter Dateigröße bereit. " +
                                "Direktes mmap-Laden ist damit nicht verifizierbar; nutze den ausdrücklichen Modellimport als Kopie."
                        )
                        else -> File(modelPath).length().takeIf { it > 0L }
                            ?: throw IllegalArgumentException("Could not determine GGUF file size for the model memory guard")
                    }

                    val memoryAdmission =
                        if (ModelMemoryGuard.requiresPostVulkanCpuMemorySettling(
                                previousBackend = previousBackend,
                                requestedBackend = config.backendPreference,
                                allowHardLimitOverride = config.memoryGuardOverride
                            )
                        ) {
                            EngineDiagnostics.info(
                                "memory",
                                "MODEL_UNLOAD_SETTLING_STARTED",
                                "previousBackend=${previousBackend},requestedBackend=CPU," +
                                    "maxWaitMs=${ModelMemoryGuard.CPU_MEMORY_SETTLING_MAX_WAIT_MS}," +
                                    "sampleIntervalMs=${ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_INTERVAL_MS}," +
                                    "stableSampleCount=${ModelMemoryGuard.CPU_MEMORY_SETTLING_SAMPLE_COUNT}"
                            )
                            awaitStableCpuMemoryAfterVulkanUnload(
                                memoryManager = memoryManager,
                                modelBytes = modelBytes,
                                config = config,
                                previousBackend = previousBackend!!
                            )
                        } else {
                            val memoryInfo = ActivityManager.MemoryInfo()
                            memoryManager.getMemoryInfo(memoryInfo)
                            val plan = ModelMemoryGuard.plan(
                                modelBytes = modelBytes,
                                requestedContextTokens = config.contextSize,
                                availableBytes = memoryInfo.availMem,
                                totalBytes = memoryInfo.totalMem,
                                lowMemory = memoryInfo.lowMemory,
                                allowHardLimitOverride = config.memoryGuardOverride,
                                // CPU requires the estimate to fit in available RAM. Vulkan
                                // retains the soft available-memory policy for UMA accounting.
                                requireAvailableMemory = config.backendPreference == BackendPreference.CPU
                            )
                            ModelMemoryAdmission(memoryInfo, plan)
                        }

                    if (!modelLoadStarted) {
                        resourceMonitor?.beginModelLoad(modelPath, config)
                        modelLoadStarted = true
                    }

                    val memoryInfo = memoryAdmission.memoryInfo
                    val memoryPlan = memoryAdmission.plan
                    val effectiveConfig = if (memoryPlan.contextReduced) {
                        config.copy(contextSize = memoryPlan.effectiveContextTokens)
                    } else config
                    EngineDiagnostics.info(
                        "memory", "MODEL_LOAD_MEMORY_PLAN",
                        "modelBytes=${memoryPlan.modelBytes},kvEstimateBytes=${memoryPlan.estimatedKvBytes}," +
                            "requiredBytes=${memoryPlan.requiredBytes},availableBytes=${memoryPlan.availableBytes}," +
                            "totalBytes=${memoryPlan.totalBytes},hardLimitBytes=${memoryPlan.hardLimitBytes}," +
                            "requestedContext=${config.contextSize},effectiveContext=${effectiveConfig.contextSize}," +
                            "contextReduced=${memoryPlan.contextReduced},pressureWarning=${memoryPlan.pressureWarning}," +
                            "hardLimitExceeded=${memoryPlan.hardLimitExceeded},overrideApplied=${memoryPlan.overrideApplied}," +
                            "strictAvailableMemory=${memoryPlan.strictAvailableMemory}," +
                            "availableLimitExceeded=${memoryPlan.availableLimitExceeded}," +
                            "availableLimitOverrideApplied=${memoryPlan.availableLimitOverrideApplied}," +
                            "lowMemory=${memoryInfo.lowMemory}"
                    )

                    val result = e.loadModel(
                        ModelSource.FilePath(
                            path = nativePath,
                            descriptor = modelDescriptor.fileDescriptor,
                            descriptorFd = modelDescriptor.fd
                        ),
                        effectiveConfig
                    )
                    result.onSuccess { info ->
                        modelLoadedSuccessfully = true
                        activeModelDescriptor = modelDescriptor
                        descriptorTransferred = true
                        val publicInfo = info.copy(name = displayName, path = modelPath)
                        resourceMonitor?.modelLoaded(publicInfo, effectiveConfig)
                        EngineDiagnostics.info(
                            "ipc",
                            "MODEL_LOADED",
                            "model=${publicInfo.name},backend=${publicInfo.activeBackend},directDescriptor=true"
                        )
                        callback?.onModelLoaded(publicInfo)
                    }.onFailure { err ->
                        EngineDiagnostics.error("ipc", "MODEL_LOAD_FAILED", err)
                        callback?.onModelLoadFailed(err.message ?: "Model load failed")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    // If a replacement was unloaded but its next memory admission failed,
                    // make that attempted load visible as a failed load in the resource timeline.
                    if (previousModelUnloaded && !modelLoadStarted) {
                        resourceMonitor?.beginModelLoad(modelPath, config)
                        modelLoadStarted = true
                    }
                    val memoryRejectionDetails = when (t) {
                        is InsufficientModelMemoryException -> t.diagnosticDetails()
                        is InsufficientAvailableModelMemoryException -> t.diagnosticDetails()
                        is ModelMemorySettlingTimeoutException -> t.diagnosticDetails()
                        else -> null
                    }
                    if (memoryRejectionDetails != null) {
                        EngineDiagnostics.info(
                            "memory",
                            "MODEL_LOAD_MEMORY_REJECTED",
                            memoryRejectionDetails
                        )
                    }
                    Log.e(TAG, "Unhandled exception in loadModel", t)
                    EngineDiagnostics.error("ipc", "MODEL_LOAD_EXCEPTION", t)
                    callback?.onModelLoadFailed(t.message ?: t.javaClass.simpleName)
                } finally {
                    // Close a descriptor whose ownership was not transferred before taking
                    // the failure snapshot, so the recorded FD count reflects post-cleanup state.
                    if (!descriptorTransferred) runCatching { modelDescriptor.close() }
                    if (!modelLoadedSuccessfully && modelLoadStarted) resourceMonitor?.modelLoadFailed()
                    progressJob?.cancel()
                }
            }
        }

        override fun unloadModel(callback: IInferenceCallback?) {
            interruptInferenceForModelOperation("unloadModel")
            resourceMonitor?.modelUnloading()
            serviceScope.launch {
                try {
                    val localEngine = engine
                    if (localEngine == null) {
                        // Unload is idempotent: no engine means there is no model to unload.
                        resourceMonitor?.modelUnloaded()
                        callback?.onModelLoaded(null)
                        return@launch
                    }
                    localEngine.unloadModel().onSuccess {
                        activeModelDescriptor?.close()
                        activeModelDescriptor = null
                        resourceMonitor?.modelUnloaded()
                        callback?.onModelLoaded(null)
                    }.onFailure { err ->
                        resourceMonitor?.modelUnloadFailed()
                        callback?.onModelLoadFailed(err.message ?: "Unload failed")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in unloadModel", t)
                    EngineDiagnostics.error("ipc", "MODEL_UNLOAD_EXCEPTION", t)
                    runCatching { callback?.onModelLoadFailed(t.message ?: "Unload error") }
                }
            }
        }

        override fun streamChat(
            requestId: String,
            request: InferenceRequest,
            callback: IInferenceCallback?
        ) {
            // There is exactly one native EngineInstance per service process. Reject
            // overlapping Binder requests instead of silently cancelling the first stream.
            //
            // The Job is created LAZY, published, and only then started. Otherwise a request
            // that finishes immediately (for example "No model loaded") could clear the slot
            // before this thread assigns the Job, leaving a completed Job in the slot that
            // rejects every later request until the process restarts.
            val slotBusy = synchronized(inferenceSlotLock) {
                val existing = activeInferenceJob
                if (existing != null && existing.isActive) {
                    true
                } else {
                    activeRequestId.set(requestId)
                    val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                        val currentReqId = requestId
                        resourceMonitor?.inferenceStarted(currentReqId)
                        var terminalCallback: (() -> Unit)? = null
                        var requestReleased = false
                        val terminalState = IpcRequestTerminalState(currentReqId)
                        try {
                            val e = initEngine()
                            var tokenCount = 0
                            val streamBuilder = StringBuilder()

                            e.streamChat(request).collect { event ->
                                // Enforce the Flow contract at the IPC boundary: the first terminal
                                // event wins. Later tokens/progress/terminals from a faulty or racing
                                // producer must never replace the request's terminal result.
                                if (activeRequestId.get() != currentReqId || terminalState.isTerminal()) {
                                    return@collect
                                }

                                when (event) {
                                    is InferenceEvent.Started -> terminalState.dispatch(currentReqId) {
                                        callback?.onStarted(currentReqId, event.promptMessageCount)
                                    }
                                    is InferenceEvent.Token -> terminalState.dispatch(currentReqId) {
                                        streamBuilder.append(event.token)
                                        tokenCount++
                                        callback?.onToken(currentReqId, event.token, event.tokenIndex)
                                    }
                                    is InferenceEvent.Progress -> terminalState.dispatch(currentReqId) {
                                        when (event.phase) {
                                            ProgressPhase.PROMPT_PREPARATION,
                                            ProgressPhase.PROMPT_EVALUATION -> callback?.onPromptProgress(
                                                currentReqId,
                                                event.promptTokensProcessed,
                                                event.promptTokensTotal,
                                                event.elapsedMs
                                            )
                                            ProgressPhase.WAITING_FOR_FIRST_TOKEN -> Unit
                                            ProgressPhase.GENERATING -> callback?.onProgress(
                                                currentReqId,
                                                event.tokensGenerated,
                                                event.elapsedMs
                                            )
                                        }
                                    }
                                    is InferenceEvent.Completed -> {
                                        // Keep the native llama.cpp ID separate from the IPC/resource ID:
                                        // token traces and Vulkan profiler rows use nativeRunId, while
                                        // INFERENCE_<requestId> RAM/thermal samples use diagnosticRunId.
                                        val nativeRunId = event.result.nativeRunId
                                            .ifBlank { event.result.diagnosticRunId }
                                        resourceMonitor?.linkNativeRunId(currentReqId, nativeRunId)
                                        val result = event.result.copy(
                                            diagnosticRunId = currentReqId,
                                            nativeRunId = nativeRunId
                                        )
                                        terminalState.terminate(currentReqId) {
                                            terminalCallback = { callback?.onCompleted(currentReqId, result) }
                                        }
                                    }
                                    is InferenceEvent.Cancelled -> {
                                        val partialText = event.partialText
                                        val generatedTokens = event.tokensGenerated
                                        terminalState.terminate(currentReqId) {
                                            terminalCallback = {
                                                callback?.onCancelled(currentReqId, partialText, generatedTokens)
                                            }
                                        }
                                    }
                                    is InferenceEvent.Error -> {
                                        val message = event.throwable.message ?: event.throwable.javaClass.simpleName
                                        val partialText = event.partialText
                                        terminalState.terminate(currentReqId) {
                                            terminalCallback = {
                                                callback?.onError(currentReqId, message, partialText)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            Log.i(TAG, "Inference coroutine cancelled for request $currentReqId")
                            terminalState.terminate(currentReqId) {
                                terminalCallback = { callback?.onCancelled(currentReqId, "", 0) }
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error in streamChat IPC", t)
                            val message = t.message ?: t.javaClass.simpleName
                            terminalState.terminate(currentReqId) {
                                terminalCallback = { callback?.onError(currentReqId, message, "") }
                            }
                        } finally {
                            resourceMonitor?.inferenceFinished(currentReqId)
                            requestReleased = activeRequestId.compareAndSet(currentReqId, null)
                            if (requestReleased) {
                                // Release the active slot before notifying the client. The client
                                // may immediately reload the model for the next benchmark profile;
                                // it must not preempt this just-finished request during the small
                                // window between onCompleted and this Job's finally block.
                                activeInferenceJob = null
                            }
                        }

                        if (requestReleased) {
                            runCatching { terminalCallback?.invoke() }
                                .onFailure { callbackError ->
                                    EngineDiagnostics.error(
                                        "ipc",
                                        "INFERENCE_TERMINAL_CALLBACK_FAILED",
                                        callbackError
                                    )
                                }
                        }
                    }
                    activeInferenceJob = job
                    job.start()
                    false
                }
            }
            if (slotBusy) {
                runCatching {
                    callback?.onError(
                        requestId,
                        "Another inference request is already active",
                        ""
                    )
                }
            }
        }

        override fun cancelInference(requestId: String?) {
            val current = activeRequestId.get()
            if (!requestId.isNullOrBlank() && requestId == current) {
                EngineDiagnostics.info("ipc", "CANCEL_INFERENCE", "Cancelling inference request: $requestId")
                // The engine's native cancellation signal is cooperative. Keep the
                // request ID and collector alive until it emits Cancelled(partialText).
                engine?.cancelInference()
            } else {
                EngineDiagnostics.info(
                    "ipc",
                    "CANCEL_INFERENCE_IGNORED",
                    "requestId=$requestId does not match active request=$current"
                )
            }
        }

        override fun killProcess() {
            val pid = Process.myPid()
            // Do not perform file logging, coroutine cancellation, joins, or engine.close()
            // before termination. Any of these can block behind a stalled native/JNI call.
            Log.w(TAG, "Immediately terminating inference process PID=$pid without native cleanup")
            Process.killProcess(pid)
            System.exit(0)
        }

        override fun armNativeInferenceBlockForTest() {
            check(BuildConfig.DEBUG) { "Native inference test hooks are unavailable in Release builds" }
            val localEngine = engine as? LocalLlmEngineImpl
                ?: throw IllegalStateException("Native inference engine is not initialized")
            localEngine.armNativeInferenceBlockForTest()
            EngineDiagnostics.info(
                "ipc",
                "DEBUG_NATIVE_INFERENCE_BLOCK_ARMED",
                "Next nativeProcessChat will block in JNI; pid=${Process.myPid()}"
            )
        }

        override fun getStatus(): String {
            if (BuildConfig.DEBUG &&
                (engine as? LocalLlmEngineImpl)?.isNativeInferenceBlockEnteredForTest() == true
            ) {
                return "DEBUG_NATIVE_INFERENCE_BLOCKED"
            }
            return engine?.state?.value?.let { state ->
                when (state) {
                    is EngineState.Uninitialized -> "UNINITIALIZED"
                    is EngineState.Initializing -> "INITIALIZING"
                    is EngineState.Ready -> "READY"
                    is EngineState.LoadingModel -> "LOADING_MODEL"
                    is EngineState.ModelLoaded -> "MODEL_LOADED"
                    is EngineState.Inferring -> "INFERRING"
                    is EngineState.Reconnecting -> "RECONNECTING"
                    is EngineState.Error -> "ERROR: ${state.message}"
                    is EngineState.Closed -> "CLOSED"
                }
            } ?: "UNINITIALIZED"
        }

        override fun getBackendInfo(): BackendInfo {
            return engine?.backendInfo?.value ?: BackendInfo(
                backendName = "llama.cpp",
                systemInfo = "Process PID ${Process.myPid()}",
                isAvailable = true,
                recommendedThreads = com.mydocanizer.llm.engine.internal.CpuThreadPolicy.recommendedThreads()
            )
        }

        override fun getProcessId(): Int {
            return Process.myPid()
        }

        override fun shutdown() {
            EngineDiagnostics.info("ipc", "SHUTDOWN_REQUESTED", "Client called shutdown()")
            serviceScope.launch {
                try {
                    activeInferenceJob?.cancel()
                    engine?.close()
                } finally {
                    stopSelf()
                }
            }
        }
    }
}
