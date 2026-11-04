package com.mydocanizer.llm.engine.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceResult
import com.mydocanizer.llm.engine.LocalLlmEngine
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android Service running in a dedicated isolated process (:inference).
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

    private var engine: LocalLlmEngine? = null
    private val activeRequestId = AtomicReference<String?>(null)
    private var activeInferenceJob: Job? = null
    private val isShuttingDown = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        EngineDiagnostics.initialize(applicationContext)
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
        val newEngine = LocalLlmEngine.create(
            applicationContext,
            useIpcProcess = false
        )
        engine = newEngine
        return newEngine
    }

    override fun onBind(intent: Intent?): IBinder {
        EngineDiagnostics.info("ipc", "SERVICE_BOUND", "Client bound to InferenceService (PID=${Process.myPid()})")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        EngineDiagnostics.info("ipc", "SERVICE_UNBOUND", "Client unbound from InferenceService")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isShuttingDown.set(true)
        EngineDiagnostics.info("ipc", "SERVICE_DESTROYED", "InferenceService destroying")
        serviceScope.launch {
            try {
                activeInferenceJob?.cancel()
                engine?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing engine on destroy", e)
            } finally {
                workerExecutor.shutdown()
            }
        }
        serviceScope.cancel()
    }

    private val binder = object : IInferenceService.Stub() {

        override fun initialize() {
            serviceScope.launch {
                try {
                    val e = initEngine()
                    e.initialize()
                } catch (e: Exception) {
                    EngineDiagnostics.error("ipc", "INIT_FAILED", e)
                }
            }
        }

        override fun loadModel(modelPath: String, config: EngineConfig, callback: IInferenceCallback?) {
            serviceScope.launch {
                var progressJob: Job? = null
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
                    EngineDiagnostics.info("ipc", "LOAD_MODEL_REQUESTED", "path=$modelPath, ctx=${config.contextSize}")
                    progressJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        e.modelLoadProgressEvents.collect { percent ->
                            runCatching { callback?.onModelLoadProgress(percent.coerceIn(0, 99)) }
                        }
                    }
                    val result = e.loadModel(ModelSource.FilePath(modelPath), config)
                    result.onSuccess { info ->
                        EngineDiagnostics.info("ipc", "MODEL_LOADED", "model=${info.name}, backend=${info.activeBackend}")
                        callback?.onModelLoaded(info)
                    }.onFailure { err ->
                        EngineDiagnostics.error("ipc", "MODEL_LOAD_FAILED", err)
                        callback?.onModelLoadFailed(err.message ?: "Model load failed")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Unhandled exception in loadModel", t)
                    callback?.onModelLoadFailed(t.message ?: t.javaClass.simpleName)
                } finally {
                    progressJob?.cancel()
                }
            }
        }

        override fun unloadModel(callback: IInferenceCallback?) {
            serviceScope.launch {
                try {
                    engine?.unloadModel()?.onSuccess {
                        callback?.onModelLoaded(null)
                    }?.onFailure { err ->
                        callback?.onModelLoadFailed(err.message ?: "Unload failed")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in unloadModel", t)
                    callback?.onModelLoadFailed(t.message ?: "Unload error")
                }
            }
        }

        override fun streamChat(
            requestId: String,
            request: InferenceRequest,
            callback: IInferenceCallback?
        ) {
            // Cancel any previously active generation before starting the new request
            activeInferenceJob?.cancel()
            activeRequestId.set(requestId)

            activeInferenceJob = serviceScope.launch {
                val currentReqId = requestId
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
                                callback?.onProgress(currentReqId, event.tokensGenerated, event.elapsedMs)
                            }
                            is InferenceEvent.Completed -> {
                                callback?.onCompleted(currentReqId, event.result)
                            }
                            is InferenceEvent.Cancelled -> {
                                callback?.onCancelled(currentReqId, event.partialText, event.tokensGenerated)
                            }
                            is InferenceEvent.Error -> {
                                callback?.onError(
                                    currentReqId,
                                    event.throwable.message ?: event.throwable.javaClass.simpleName,
                                    event.partialText
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Log.i(TAG, "Inference coroutine cancelled for request $currentReqId")
                    callback?.onCancelled(currentReqId, "", 0)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in streamChat IPC", t)
                    callback?.onError(currentReqId, t.message ?: t.javaClass.simpleName, "")
                } finally {
                    if (activeRequestId.get() == currentReqId) {
                        activeRequestId.set(null)
                        activeInferenceJob = null
                    }
                }
            }
        }

        override fun cancelInference(requestId: String?) {
            val current = activeRequestId.get()
            if (requestId.isNullOrBlank() || requestId == current) {
                EngineDiagnostics.info("ipc", "CANCEL_INFERENCE", "Cancelling inference request: $requestId")
                engine?.cancelInference()
                activeInferenceJob?.cancel()
                activeInferenceJob = null
                activeRequestId.set(null)
            }
        }

        override fun killProcess() {
            EngineDiagnostics.info("ipc", "SELF_KILL_REQUESTED", "InferenceService self-kill initiated (PID=${Process.myPid()})")
            Log.w(TAG, "Self-kill initiated for process PID=${Process.myPid()}")
            try {
                activeInferenceJob?.cancel()
                engine?.close()
            } catch (ignored: Exception) {}
            Process.killProcess(Process.myPid())
            System.exit(0)
        }

        override fun getStatus(): String {
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
                recommendedThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
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
