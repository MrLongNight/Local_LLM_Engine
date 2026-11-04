package com.mydocanizer.llm.engine.ipc

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import com.mydocanizer.llm.engine.InferenceTimeoutException
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import com.mydocanizer.llm.engine.internal.ModelSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * Internal IPC client encapsulating the AIDL connection to the dedicated :inference process.
 *
 * Implements [LocalLlmEngine] with:
 * - Centralized, tiered, monotonic [TimeoutConfig] using [System.nanoTime].
 * - Automated process-kill escalation as Ultima Ratio on any timeout.
 * - Granular [FinishReason] error reporting for each tiered timeout phase.
 * - Serialized operations (Mutex) preventing race conditions.
 * - Dynamic reconnection lifecycle through [EngineState.Reconnecting].
 * - Request-ID versioning to ensure stale events never corrupt engine state.
 */
class InferenceClient(
    private val context: Context,
    private val mockService: IInferenceService? = null,
    coroutineContext: CoroutineContext = Dispatchers.Default,
    var timeoutConfig: TimeoutConfig = TimeoutConfig(),
    private val processLivenessCheck: ((pid: Int) -> Boolean)? = null
) : LocalLlmEngine {

    companion object {
        private const val TAG = "InferenceClient"
        private const val WATCHDOG_CHECK_INTERVAL_MS = 25L
        private const val KILL_CHECK_INTERVAL_MS = 25L
    }

    private val clientScope = CoroutineScope(coroutineContext + SupervisorJob())
    private val operationMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    override val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _backendInfo = MutableStateFlow(BackendInfo())
    override val backendInfo: StateFlow<BackendInfo> = _backendInfo.asStateFlow()

    private val _modelLoadProgress = MutableStateFlow<Int?>(null)
    override val modelLoadProgress: StateFlow<Int?> = _modelLoadProgress.asStateFlow()

    private val _remotePid = MutableStateFlow<Int?>(null)
    val remotePid: StateFlow<Int?> = _remotePid.asStateFlow()

    private var remoteService: IInferenceService? = mockService
    private var isBound = false
    private var serviceConnectionDeferred = CompletableDeferred<IInferenceService>()

    private val activeRequestId = AtomicReference<String?>(null)
    private var lastLoadedSource: ModelSource? = null
    private var lastLoadedConfig: EngineConfig? = null
    private val isClosed = AtomicBoolean(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                Log.w(TAG, "onServiceConnected received null binder")
                return
            }
            val service = IInferenceService.Stub.asInterface(binder) ?: return
            remoteService = service
            if (!serviceConnectionDeferred.isCompleted) {
                serviceConnectionDeferred.complete(service)
            }
            try {
                val pid = service.processId
                _remotePid.value = pid
                _backendInfo.value = service.backendInfo ?: BackendInfo()
                _state.value = if (_currentModel.value != null) {
                    EngineState.ModelLoaded(_currentModel.value!!)
                } else {
                    EngineState.Ready
                }
                EngineDiagnostics.info("ipc_client", "SERVICE_CONNECTED", "Remote service PID=$pid connected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query connected service properties", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Reconnecting
            serviceConnectionDeferred = CompletableDeferred()
            EngineDiagnostics.info("ipc_client", "SERVICE_DISCONNECTED", "Service disconnected, reconnecting")
            if (!isClosed.get()) {
                triggerReconnect()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Reconnecting
            serviceConnectionDeferred = CompletableDeferred()
            EngineDiagnostics.error("ipc_client", "BINDING_DIED", IllegalStateException("Inference process died"))
            if (!isClosed.get()) {
                triggerReconnect()
            }
        }
    }

    init {
        if (mockService != null) {
            remoteService = mockService
            serviceConnectionDeferred.complete(mockService)
            _state.value = EngineState.Ready
            try {
                _remotePid.value = mockService.processId
                _backendInfo.value = mockService.backendInfo ?: BackendInfo()
            } catch (ignored: Exception) {}
        } else {
            bindToService()
        }
    }

    private fun bindToService() {
        val intent = Intent(context, InferenceService::class.java).apply {
            action = InferenceService.ACTION_BIND_INFERENCE
        }
        isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            _state.value = EngineState.Error("Failed to bind to InferenceService (:inference)")
            EngineDiagnostics.error("ipc_client", "BIND_FAILED")
        }
    }

    private fun triggerReconnect() {
        clientScope.launch {
            val startNano = System.nanoTime()
            val effective = lastLoadedConfig?.timeoutConfig ?: timeoutConfig
            try {
                _state.value = EngineState.Reconnecting
                if (isBound) {
                    try {
                        context.unbindService(serviceConnection)
                    } catch (ignored: Exception) {}
                    isBound = false
                }
                bindToService()

                // reconnectMs bounds only service reconnection. A model reload is a separate
                // operation and must use modelLoadMs, not the much shorter reconnect deadline.
                val service = withTimeout(effective.reconnectMs) {
                    getService()
                }
                EngineDiagnostics.info(
                    "ipc_client",
                    "SERVICE_RECONNECTED",
                    "Inference process PID=${service.processId}; starting recovery"
                )
                service.initialize()

                val lastSource = lastLoadedSource
                val lastConfig = lastLoadedConfig
                if (lastSource != null && lastConfig != null) {
                    _state.value = EngineState.LoadingModel(lastSource)
                    val reloadResult = loadModelInternal(service, lastSource, lastConfig)
                    reloadResult.exceptionOrNull()?.let { error ->
                        EngineDiagnostics.error("ipc_client", "MODEL_RELOAD_FAILED", error)
                    }
                } else {
                    _state.value = EngineState.Ready
                }
            } catch (tce: TimeoutCancellationException) {
                val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
                Log.e(TAG, "Service reconnection timed out after ${elapsedMs}ms", tce)
                _state.value = EngineState.Error("Service reconnection timed out ($elapsedMs ms)")
                // Do not kill an idle service via cancelInference(); no inference request is active.
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection failed", e)
                _state.value = EngineState.Error("Failed to reconnect: ${e.message}")
            }
        }
    }

    private suspend fun getService(): IInferenceService {
        val effective = lastLoadedConfig?.timeoutConfig ?: timeoutConfig
        val startNano = System.nanoTime()
        return try {
            withTimeout(effective.serviceBindMs) {
                remoteService ?: serviceConnectionDeferred.await()
            }
        } catch (tce: TimeoutCancellationException) {
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
            Log.e(TAG, "Service bind timed out after ${elapsedMs}ms")
            cancelInference()
            throw InferenceTimeoutException(FinishReason.TIMEOUT_SERVICE_BIND, elapsedMs)
        }
    }

    fun isProcessAlive(pid: Int): Boolean {
        processLivenessCheck?.let { return it(pid) }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val procs = am?.runningAppProcesses
            if (procs != null) {
                return procs.any { it.pid == pid }
            }
        } catch (ignored: Exception) {}
        return try {
            File("/proc/$pid").exists()
        } catch (ignored: Exception) {
            false
        }
    }

    override suspend fun initialize(): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val service = getService()
                service.initialize()
                _backendInfo.value = service.backendInfo ?: BackendInfo()
                _remotePid.value = service.processId
                _state.value = EngineState.Ready
            }
        }
    }

    override suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val service = getService()
            loadModelInternal(service, source, config)
        }
    }

    private suspend fun loadModelInternal(
        service: IInferenceService,
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> {
        val deferred = CompletableDeferred<Result<ModelInfo>>()
        val effectiveTimeout = config.timeoutConfig
        val startNano = System.nanoTime()

        try {
            _state.value = EngineState.LoadingModel(source)
            val file: File = ModelSourceResolver.resolveToFile(context, source)
            _modelLoadProgress.value = 0

            val callback = object : IInferenceCallback.Stub() {
                override fun onModelLoaded(modelInfo: ModelInfo?) {
                    if (modelInfo != null) {
                        // Keep only a model confirmed by the service as the automatic
                        // recovery target after a later process restart.
                        lastLoadedSource = source
                        lastLoadedConfig = config
                        _modelLoadProgress.value = 100
                        _currentModel.value = modelInfo
                        _state.value = EngineState.ModelLoaded(modelInfo)
                        deferred.complete(Result.success(modelInfo))
                    } else {
                        _currentModel.value = null
                        lastLoadedSource = null
                        lastLoadedConfig = null
                        _state.value = EngineState.Ready
                        deferred.complete(Result.failure(IllegalStateException("Empty model info returned")))
                    }
                }

                override fun onModelLoadFailed(errorMessage: String?) {
                    _modelLoadProgress.value = null
                    val msg = errorMessage ?: "Model loading failed"
                    _state.value = EngineState.Error(msg)
                    deferred.complete(Result.failure(IllegalStateException(msg)))
                }

                override fun onModelLoadProgress(progressPercent: Int) {
                    _modelLoadProgress.value = progressPercent.coerceIn(0, 100)
                }

                override fun onStarted(requestId: String?, promptMessageCount: Int) {}
                override fun onToken(requestId: String?, token: String?, tokenIndex: Int) {}
                override fun onProgress(requestId: String?, tokensGenerated: Int, elapsedMs: Long) {}
                override fun onCompleted(requestId: String?, result: InferenceResult?) {}
                override fun onCancelled(requestId: String?, partialText: String?, tokensGenerated: Int) {}
                override fun onError(requestId: String?, errorMessage: String?, partialText: String?) {}
            }

            service.loadModel(file.absolutePath, config, callback)

            return try {
                withTimeout(effectiveTimeout.modelLoadMs) {
                    deferred.await()
                }
            } catch (tce: TimeoutCancellationException) {
                val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
                Log.e(TAG, "Model loading timed out after ${elapsedMs}ms ($effectiveTimeout)")
                // Model loading has no inference request ID. Abort the isolated process
                // explicitly only after the model-load deadline expires.
                abortRemoteProcess(requestId = null, reason = "model_load_timeout")
                _modelLoadProgress.value = null
                val timeoutEx = InferenceTimeoutException(FinishReason.TIMEOUT_MODEL_LOAD, elapsedMs)
                _state.value = EngineState.Error(timeoutEx.message ?: "Model load timed out", timeoutEx)
                Result.failure(timeoutEx)
            }
        } catch (t: Throwable) {
            _state.value = EngineState.Error(t.message ?: "Failed to initiate model load")
            return Result.failure(t)
        }
    }

    override suspend fun unloadModel(): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<Result<Unit>>()
            try {
                val service = getService()
                service.unloadModel(object : IInferenceCallback.Stub() {
                    override fun onModelLoaded(modelInfo: ModelInfo?) {
                        _currentModel.value = null
                        lastLoadedSource = null
                        lastLoadedConfig = null
                        _state.value = EngineState.Ready
                        deferred.complete(Result.success(Unit))
                    }

                    override fun onModelLoadFailed(errorMessage: String?) {
                        _modelLoadProgress.value = null
                        deferred.complete(Result.failure(IllegalStateException(errorMessage ?: "Unload failed")))
                    }

                    override fun onModelLoadProgress(progressPercent: Int) {}

                    override fun onStarted(requestId: String?, promptMessageCount: Int) {}
                    override fun onToken(requestId: String?, token: String?, tokenIndex: Int) {}
                    override fun onProgress(requestId: String?, tokensGenerated: Int, elapsedMs: Long) {}
                    override fun onCompleted(requestId: String?, result: InferenceResult?) {}
                    override fun onCancelled(requestId: String?, partialText: String?, tokensGenerated: Int) {}
                    override fun onError(requestId: String?, errorMessage: String?, partialText: String?) {}
                })
            } catch (t: Throwable) {
                deferred.complete(Result.failure(t))
            }
            deferred.await()
        }
    }

    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val currentModelInfo = _currentModel.value
        val requestId = UUID.randomUUID().toString()
        activeRequestId.set(requestId)
        _state.value = EngineState.Inferring(request)

        val effectiveTimeout = request.timeoutConfig
            ?: lastLoadedConfig?.timeoutConfig
            ?: timeoutConfig

        val startNano = System.nanoTime()
        var lastTokenNano = startNano
        var receivedFirstToken = false
        var receivedStarted = false
        var tokenCount = 0
        val streamBuilder = StringBuilder()

        fun triggerTimeout(reason: FinishReason, elapsedMs: Long) {
            if (activeRequestId.compareAndSet(requestId, null)) {
                EngineDiagnostics.error("ipc_client", "INFERENCE_TIMEOUT", InferenceTimeoutException(reason, elapsedMs))
                Log.w(TAG, "Inference timeout ($reason) after ${elapsedMs}ms. Executing process-kill as Ultima Ratio.")

                // A timeout is a hard-abort condition. The active ID was cleared above,
                // so call the process-abort path directly rather than the public cancel API.
                abortRemoteProcess(requestId = requestId, reason = "inference_timeout_${reason}")

                val partial = streamBuilder.toString()
                val result = InferenceResult(
                    text = partial,
                    tokensGenerated = tokenCount,
                    durationMs = elapsedMs,
                    tokensPerSecond = if (elapsedMs > 0) (tokenCount.toDouble() * 1000.0) / elapsedMs else 0.0,
                    finishReason = reason
                )
                trySend(InferenceEvent.Completed(result))
                trySend(InferenceEvent.Error(InferenceTimeoutException(reason, elapsedMs), partial))
                channel.close()
            }
        }

        // Tiered monotonic watchdog using System.nanoTime()
        val watchdogJob = launch {
            while (isActive && activeRequestId.get() == requestId) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                val now = System.nanoTime()
                val elapsedTotalMs = (now - startNano) / 1_000_000L

                // 1. Total inference timeout check
                if (elapsedTotalMs > effectiveTimeout.totalInferenceMs) {
                    triggerTimeout(FinishReason.TIMEOUT_TOTAL_INFERENCE, elapsedTotalMs)
                    break
                }

                // 2. Prefill timeout check (before started event or first token)
                if (!receivedStarted && !receivedFirstToken) {
                    val elapsedPrefillMs = (now - startNano) / 1_000_000L
                    if (elapsedPrefillMs > effectiveTimeout.prefillMs) {
                        triggerTimeout(FinishReason.TIMEOUT_PREFILL, elapsedPrefillMs)
                        break
                    }
                }

                // 3. First token timeout check
                if (!receivedFirstToken) {
                    val elapsedFirstTokenMs = (now - startNano) / 1_000_000L
                    if (elapsedFirstTokenMs > effectiveTimeout.firstTokenMs) {
                        triggerTimeout(FinishReason.TIMEOUT_FIRST_TOKEN, elapsedFirstTokenMs)
                        break
                    }
                } else {
                    // 4. Inter-token timeout check
                    val elapsedInterTokenMs = (now - lastTokenNano) / 1_000_000L
                    if (elapsedInterTokenMs > effectiveTimeout.interTokenMs) {
                        triggerTimeout(FinishReason.TIMEOUT_INTER_TOKEN, elapsedInterTokenMs)
                        break
                    }
                }
            }
        }

        val callback = object : IInferenceCallback.Stub() {
            override fun onStarted(reqId: String?, promptMessageCount: Int) {
                if (reqId == activeRequestId.get()) {
                    receivedStarted = true
                    trySend(InferenceEvent.Started(promptMessageCount))
                }
            }

            override fun onToken(reqId: String?, token: String?, tokenIndex: Int) {
                if (reqId == activeRequestId.get() && token != null) {
                    receivedFirstToken = true
                    lastTokenNano = System.nanoTime()
                    tokenCount++
                    streamBuilder.append(token)
                    trySend(InferenceEvent.Token(token, tokenIndex))
                }
            }

            override fun onProgress(reqId: String?, tokensGenerated: Int, elapsedMs: Long) {
                if (reqId == activeRequestId.get()) {
                    trySend(InferenceEvent.Progress(tokensGenerated, elapsedMs))
                }
            }

            override fun onCompleted(reqId: String?, result: InferenceResult?) {
                if (reqId == activeRequestId.get() && result != null) {
                    watchdogJob.cancel()
                    trySend(InferenceEvent.Completed(result))
                    activeRequestId.compareAndSet(reqId, null)
                    channel.close()
                }
            }

            override fun onCancelled(reqId: String?, partialText: String?, tokensGenerated: Int) {
                if (reqId == activeRequestId.get()) {
                    watchdogJob.cancel()
                    trySend(InferenceEvent.Cancelled(partialText ?: "", tokensGenerated))
                    activeRequestId.compareAndSet(reqId, null)
                    channel.close()
                }
            }

            override fun onError(reqId: String?, errorMessage: String?, partialText: String?) {
                if (reqId == activeRequestId.get()) {
                    watchdogJob.cancel()
                    trySend(
                        InferenceEvent.Error(
                            IllegalStateException(errorMessage ?: "Unknown inference error"),
                            partialText ?: ""
                        )
                    )
                    activeRequestId.compareAndSet(reqId, null)
                    channel.close()
                }
            }

            override fun onModelLoaded(modelInfo: ModelInfo?) {}
            override fun onModelLoadFailed(errorMessage: String?) {}
            override fun onModelLoadProgress(progressPercent: Int) {}
        }

        try {
            val service = getService()
            service.streamChat(requestId, request, callback)
        } catch (e: Exception) {
            watchdogJob.cancel()
            trySend(InferenceEvent.Error(e))
            channel.close()
        }

        awaitClose {
            watchdogJob.cancel()
            if (activeRequestId.compareAndSet(requestId, null)) {
                try {
                    remoteService?.cancelInference(requestId)
                } catch (ignored: Exception) {}
            }
            if (currentModelInfo != null) {
                _state.value = EngineState.ModelLoaded(currentModelInfo)
            } else {
                _state.value = EngineState.Ready
            }
        }
    }

    override suspend fun generateChat(request: InferenceRequest): Result<InferenceResult> {
        return runCatching {
            var completed: InferenceResult? = null
            var lastError: Throwable? = null

            streamChat(request).collect { event ->
                when (event) {
                    is InferenceEvent.Completed -> completed = event.result
                    is InferenceEvent.Cancelled -> {
                        completed = InferenceResult(
                            text = event.partialText,
                            tokensGenerated = event.tokensGenerated,
                            durationMs = 0L,
                            tokensPerSecond = 0.0,
                            finishReason = FinishReason.CANCELLED
                        )
                    }
                    is InferenceEvent.Error -> lastError = event.throwable
                    else -> {}
                }
            }

            completed ?: throw (lastError ?: IllegalStateException("Generation did not produce a result"))
        }
    }

    override fun cancelInference() {
        val requestId = activeRequestId.getAndSet(null)
        if (requestId == null) {
            // An idle cancel must not tear down :inference or discard an already loaded model.
            EngineDiagnostics.info(
                "ipc_client",
                "CANCEL_IGNORED_NO_ACTIVE_REQUEST",
                "No active inference request; remote PID=${_remotePid.value} remains running"
            )
            return
        }

        EngineDiagnostics.info(
            "ipc_client",
            "CANCEL_REQUESTED",
            "Cancelling active request=$requestId, remotePid=${_remotePid.value}"
        )
        abortRemoteProcess(requestId = requestId, reason = "inference_cancelled")
    }

    /**
     * Terminates the isolated process only after a real abort condition.
     *
     * requestId may be null for operations such as model loading, which have no inference
     * request ID but have independently exceeded their configured timeout.
     */
    private fun abortRemoteProcess(requestId: String?, reason: String) {
        val targetPid = _remotePid.value
        val service = remoteService
        val effective = lastLoadedConfig?.timeoutConfig ?: timeoutConfig

        EngineDiagnostics.info(
            "ipc_client",
            "PROCESS_ABORT_REQUESTED",
            "reason=$reason, request=$requestId, remotePid=$targetPid"
        )

        if (service != null && targetPid != null) {
            try {
                service.killProcess()
            } catch (e: Exception) {
                Log.w(TAG, "Remote process termination request completed or process exited", e)
            }
        } else if (service != null && requestId != null) {
            try {
                service.cancelInference(requestId)
            } catch (ignored: Exception) {}
        }

        if (targetPid != null && targetPid != Process.myPid()) {
            clientScope.launch {
                var isAlive = isProcessAlive(targetPid)
                val deadline = System.currentTimeMillis() + effective.abortGracePeriodMs

                while (isAlive && System.currentTimeMillis() < deadline) {
                    delay(KILL_CHECK_INTERVAL_MS)
                    isAlive = isProcessAlive(targetPid)
                }

                if (isAlive) {
                    Log.w(TAG, "Service process PID=$targetPid still alive after abort grace period. Hard killing process.")
                    EngineDiagnostics.info("ipc_client", "HARD_KILL", "Hard killing service process PID=$targetPid")
                    try {
                        Process.killProcess(targetPid)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed hard kill of PID=$targetPid", e)
                    }
                }

                if (mockService == null && !isClosed.get()) {
                    _state.value = EngineState.Reconnecting
                    triggerReconnect()
                }
            }
        }
    }

    override fun close() {
        isClosed.set(true)
        activeRequestId.set(null)
        try {
            remoteService?.shutdown()
            if (isBound) {
                context.unbindService(serviceConnection)
                isBound = false
            }
        } catch (ignored: Exception) {}
        clientScope.cancel()
        _state.value = EngineState.Closed
    }
}

/**
 * Typealias for backwards compatibility.
 */
typealias InferenceServiceClient = InferenceClient
