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
        if (activeInferenceJob == null) return
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
            resourceMonitor?.beginModelLoad(modelPath, config)
            serviceScope.launch {
                var progressJob: Job? = null
                var modelLoadedSuccessfully = false
                var descriptorTransferred = false
                try {
                    val e = initEngine()
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

                    // Unload an old mmap before closing the descriptor it uses.
                    activeModelDescriptor?.let { previous ->
                        e.unloadModel().getOrThrow()
                        activeModelDescriptor = null
                        previous.close()
                    }

                    // Preflight after releasing the previous model: estimate GGUF + KV +
                    // allocator headroom and reduce context before native model allocation.
                    val memoryManager = getSystemService(ActivityManager::class.java)
                        ?: throw IllegalStateException("Android ActivityManager is unavailable; cannot safely estimate model-load memory")
                    val memoryInfo = ActivityManager.MemoryInfo()
                    memoryManager.getMemoryInfo(memoryInfo)
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
                    val memoryPlan = ModelMemoryGuard.plan(
                        modelBytes = modelBytes,
                        requestedContextTokens = config.contextSize,
                        availableBytes = memoryInfo.availMem,
                        totalBytes = memoryInfo.totalMem,
                        lowMemory = memoryInfo.lowMemory,
                        allowHardLimitOverride = config.memoryGuardOverride
                    )
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
                    Log.e(TAG, "Unhandled exception in loadModel", t)
                    EngineDiagnostics.error("ipc", "MODEL_LOAD_EXCEPTION", t)
                    callback?.onModelLoadFailed(t.message ?: t.javaClass.simpleName)
                } finally {
                    if (!modelLoadedSuccessfully) resourceMonitor?.modelLoadFailed()
                    if (!descriptorTransferred) runCatching { modelDescriptor.close() }
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
            if (activeInferenceJob != null) {
                runCatching {
                    callback?.onError(
                        requestId,
                        "Another inference request is already active",
                        ""
                    )
                }
                return
            }
            activeRequestId.set(requestId)

            activeInferenceJob = serviceScope.launch {
                val currentReqId = requestId
                resourceMonitor?.inferenceStarted(currentReqId)
                var terminalCallback: (() -> Unit)? = null
                var requestReleased = false
                try {
                    val e = initEngine()
                    var tokenCount = 0
                    val streamBuilder = StringBuilder()

                    e.streamChat(request).collect { event ->
                        // Discard events if request ID has been superseded or cancelled
                        if (activeRequestId.get() != currentReqId) return@collect

                        when (event) {
                            is InferenceEvent.Started -> {
                                callback?.onStarted(currentReqId, event.promptMessageCount)
                            }
                            is InferenceEvent.Token -> {
                                streamBuilder.append(event.token)
                                tokenCount++
                                callback?.onToken(currentReqId, event.token, event.tokenIndex)
                            }
                            is InferenceEvent.Progress -> {
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
                                val result = event.result.copy(
                                    diagnosticRunId = currentReqId,
                                    nativeRunId = nativeRunId
                                )
                                terminalCallback = { callback?.onCompleted(currentReqId, result) }
                            }
                            is InferenceEvent.Cancelled -> {
                                val partialText = event.partialText
                                val generatedTokens = event.tokensGenerated
                                terminalCallback = {
                                    callback?.onCancelled(currentReqId, partialText, generatedTokens)
                                }
                            }
                            is InferenceEvent.Error -> {
                                val message = event.throwable.message ?: event.throwable.javaClass.simpleName
                                val partialText = event.partialText
                                terminalCallback = {
                                    callback?.onError(currentReqId, message, partialText)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Log.i(TAG, "Inference coroutine cancelled for request $currentReqId")
                    terminalCallback = { callback?.onCancelled(currentReqId, "", 0) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in streamChat IPC", t)
                    val message = t.message ?: t.javaClass.simpleName
                    terminalCallback = { callback?.onError(currentReqId, message, "") }
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
        }

        override fun cancelInference(requestId: String?) {
            val current = activeRequestId.get()
            if (requestId.isNullOrBlank() || requestId == current) {
                EngineDiagnostics.info("ipc", "CANCEL_INFERENCE", "Cancelling inference request: $requestId")
                // The engine's native cancellation signal is cooperative. Keep the
                // request ID and collector alive until it emits Cancelled(partialText).
                engine?.cancelInference()
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
