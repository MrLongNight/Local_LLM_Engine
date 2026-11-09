package com.mydocanizer.llm.engine.ipc

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceEvent.ProgressPhase
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
internal class InferenceClient(
    private val context: Context,
    private val mockService: IInferenceService? = null,
    coroutineContext: CoroutineContext = Dispatchers.Default,
    var timeoutConfig: TimeoutConfig = TimeoutConfig(),
    private val processLivenessCheck: ((pid: Int) -> Boolean)? = null
) : LocalLlmEngine {

    private data class PendingModelLoad(
        val processId: Int?,
        val deferred: CompletableDeferred<Result<ModelInfo>>
    )

    private data class ActiveStreamControl(
        val requestId: String,
        val cancelAfterAbort: () -> Unit,
        val failAfterDisconnect: (Throwable) -> Unit
    )

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
    private val _modelLoadProgressEvents = MutableSharedFlow<Int>(extraBufferCapacity = 128)
    override val modelLoadProgressEvents: SharedFlow<Int> = _modelLoadProgressEvents.asSharedFlow()

    private val _remotePid = MutableStateFlow<Int?>(null)
    val remotePid: StateFlow<Int?> = _remotePid.asStateFlow()

    private var remoteService: IInferenceService? = mockService
    private var isBound = false
    private var serviceConnectionDeferred = CompletableDeferred<IInferenceService>()

    private val activeRequestId = AtomicReference<String?>(null)
    private val activeStreamControl = AtomicReference<ActiveStreamControl?>(null)
    private val pendingModelLoad = AtomicReference<PendingModelLoad?>(null)
    private val reconnectInProgress = AtomicBoolean(false)
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
            val disconnectedPid = _remotePid.value
            failPendingModelLoad(disconnectedPid, "SERVICE_DISCONNECTED")
            recordRemoteProcessExit(disconnectedPid, "SERVICE_DISCONNECTED")
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Reconnecting
            serviceConnectionDeferred = CompletableDeferred()
            activeStreamControl.get()
                ?.takeIf { it.requestId == activeRequestId.get() }
                ?.failAfterDisconnect(IllegalStateException("Inference service disconnected (PID=$disconnectedPid)"))
            EngineDiagnostics.info("ipc_client", "SERVICE_DISCONNECTED", "Service disconnected, reconnecting")
            if (!isClosed.get()) {
                triggerReconnect()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            val disconnectedPid = _remotePid.value
            failPendingModelLoad(disconnectedPid, "BINDING_DIED")
            recordRemoteProcessExit(disconnectedPid, "BINDING_DIED")
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Reconnecting
            serviceConnectionDeferred = CompletableDeferred()
            activeStreamControl.get()
                ?.takeIf { it.requestId == activeRequestId.get() }
                ?.failAfterDisconnect(IllegalStateException("Inference service binding died (PID=$disconnectedPid)"))
            EngineDiagnostics.error("ipc_client", "BINDING_DIED", IllegalStateException("Inference process died"))
            if (!isClosed.get()) {
                triggerReconnect()
            }
        }
    }

    init {
        // The library client owns its app-process diagnostics lifecycle. Do not rely
        // on a demo Activity having initialized the process log before embedding IPC.
        if (mockService == null) {
            // Only the real Android IPC client owns persistent app-process diagnostics.
            // Mock-service unit tests intentionally use lightweight Context wrappers.
            EngineDiagnostics.initialize(context.applicationContext)
        }
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

    /**
     * Fail only a model load owned by the process that actually disconnected.
     * This releases the original caller immediately instead of letting its 180s
     * timeout act on a replacement service process.
     */
    private fun failPendingModelLoad(disconnectedPid: Int?, event: String) {
        val pending = pendingModelLoad.get() ?: return
        if (pending.processId != null && disconnectedPid != null &&
            pending.processId != disconnectedPid
        ) {
            return
        }
        if (!pendingModelLoad.compareAndSet(pending, null)) return

        val pidLabel = pending.processId?.toString() ?: "unknown"
        val error = IllegalStateException(
            "Inference process PID=$pidLabel disconnected during model loading"
        )
        _modelLoadProgress.value = null
        _state.value = EngineState.Error(error.message ?: "Inference process disconnected", error)
        EngineDiagnostics.error("ipc_client", event, error)
        pending.deferred.complete(Result.failure(error))
    }

    /**
     * Reads Android's historical exit record for the exact inference PID that disconnected.
     * This distinguishes native crashes, low-memory kills and signal termination from a
     * normal Binder disconnect. The API is available from Android 11 (API 30).
     */
    private fun recordRemoteProcessExit(pid: Int?, callback: String) {
        if (pid == null || pid <= 0) return

        clientScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                EngineDiagnostics.info(
                    "ipc_client",
                    "PROCESS_EXIT_INFO_UNAVAILABLE",
                    "pid=$pid,callback=$callback,reason=requires_api_30"
                )
                return@launch
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                EngineDiagnostics.info(
                    "ipc_client",
                    "PROCESS_EXIT_INFO_UNAVAILABLE",
                    "pid=$pid,callback=$callback,reason=activity_manager_unavailable"
                )
                return@launch
            }

            val exitInfo = runCatching {
                activityManager.getHistoricalProcessExitReasons(context.packageName, pid, 8)
                    .firstOrNull { it.pid == pid }
            }.onFailure { error ->
                EngineDiagnostics.error("ipc_client", "PROCESS_EXIT_INFO_QUERY_FAILED", error)
            }.getOrNull()

            if (exitInfo == null) {
                EngineDiagnostics.info(
                    "ipc_client",
                    "PROCESS_EXIT_INFO_UNAVAILABLE",
                    "pid=$pid,callback=$callback,reason=no_matching_system_record"
                )
                return@launch
            }

            val reasonName = when (exitInfo.reason) {
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_OTHER -> "OTHER"
                else -> "UNKNOWN"
            }
            val description = exitInfo.description
                ?.replace(Regex("\\s+"), " ")
                ?.take(320)
                ?: "none"

            EngineDiagnostics.info(
                "ipc_client",
                "PROCESS_EXIT_INFO",
                "pid=$pid,callback=$callback,process=${exitInfo.processName}," +
                    "reason=$reasonName,reasonCode=${exitInfo.reason},status=${exitInfo.status}," +
                    "importance=${exitInfo.importance},pssKb=${exitInfo.pss},rssKb=${exitInfo.rss}," +
                    "timestamp=${exitInfo.timestamp},description=$description"
            )
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
        if (isClosed.get() || !reconnectInProgress.compareAndSet(false, true)) return

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

                // Invalidate old Binder-derived state before binding the replacement service.
                // This prevents getService() from returning a stale proxy during reconnect.
                remoteService = null
                _remotePid.value = null
                _currentModel.value = null
                _modelLoadProgress.value = null
                serviceConnectionDeferred = CompletableDeferred()
                bindToService()

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
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection failed", e)
                _state.value = EngineState.Error("Failed to reconnect: ${e.message}")
            } finally {
                reconnectInProgress.set(false)
            }
        }
    }

    private suspend fun getService(
        effective: TimeoutConfig = lastLoadedConfig?.timeoutConfig ?: timeoutConfig
    ): IInferenceService {
        val startNano = System.nanoTime()
        return try {
            withTimeout(effective.serviceBindMs) {
                remoteService ?: serviceConnectionDeferred.await()
            }
        } catch (tce: TimeoutCancellationException) {
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
            Log.e(TAG, "Service bind timed out after ${elapsedMs}ms")
            // A bind failure does not identify or authorize cancellation of an
            // unrelated active request. Report the bind timeout to this operation only.
            throw InferenceTimeoutException(FinishReason.TIMEOUT_SERVICE_BIND, elapsedMs)
        }
    }

    fun isProcessAlive(pid: Int): Boolean {
        processLivenessCheck?.let { return it(pid) }
        // A successful /proc lookup is authoritative for this same-UID child process.
        // In particular, a missing entry means the process is gone. Do not then consult
        // ActivityManager: its throttled process list can retain stale PIDs and turn a
        // confirmed process exit into a false "still alive" result.
        return try {
            File("/proc/$pid").exists()
        } catch (procError: Exception) {
            // Use ActivityManager only if the /proc check itself cannot be performed.
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.any { it.pid == pid } == true
            } catch (activityManagerError: Exception) {
                EngineDiagnostics.error(
                    "ipc_client",
                    "PROCESS_LIVENESS_CHECK_FAILED",
                    IllegalStateException(
                        "Could not inspect /proc/$pid or ActivityManager",
                        activityManagerError
                    )
                )
                false
            }
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
            try {
                // A new model must use its own bind deadline, not the previous model's config.
                val service = getService(config.timeoutConfig)
                loadModelInternal(service, source, config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                EngineDiagnostics.error("ipc_client", "MODEL_LOAD_SETUP_FAILED", error)
                Result.failure(error)
            }
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
        var registeredPendingLoad: PendingModelLoad? = null
        var modelDescriptor: ParcelFileDescriptor? = null

        try {
            _state.value = EngineState.LoadingModel(source)
            val modelPath: String
            val displayName: String
            // Hold the opened descriptor in a non-null local for the complete Binder
            // handoff. The nullable field exists only so the outer catch can clean up
            // if any operation between opening and the handoff fails.
            val openedDescriptor: ParcelFileDescriptor = when (source) {
                is ModelSource.ContentUri -> {
                    val uri = Uri.parse(source.uriString)
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalArgumentException("Android could not open the selected GGUF file directly")
                    displayName = source.displayName ?: runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment ?: "model.gguf"
                    modelPath = source.uriString
                    EngineDiagnostics.info(
                        "ipc_client",
                        "MODEL_SOURCE_OPENED_DIRECTLY",
                        "source=SAF,displayName=$displayName,copy=false"
                    )
                    descriptor
                }
                else -> {
                    val file: File = ModelSourceResolver.resolveToFile(context, source)
                    modelPath = file.absolutePath
                    displayName = file.name
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            modelDescriptor = openedDescriptor
            val targetPid = runCatching { service.processId }.getOrNull()
            val pending = PendingModelLoad(targetPid, deferred)
            if (!pendingModelLoad.compareAndSet(null, pending)) {
                throw IllegalStateException("Another model-load request is already pending")
            }
            registeredPendingLoad = pending
            _modelLoadProgress.value = 0
            _modelLoadProgressEvents.tryEmit(0)

        val callback = object : IInferenceCallback.Stub() {
                override fun onModelLoaded(modelInfo: ModelInfo?) {
                    if (!pendingModelLoad.compareAndSet(pending, null)) return
                    if (modelInfo != null) {
                        // Keep only a model confirmed by the service as the automatic
                        // recovery target after a later process restart.
                        lastLoadedSource = source
                        lastLoadedConfig = config
                        _modelLoadProgress.value = 100
                        _modelLoadProgressEvents.tryEmit(100)
                        val publicInfo = if (source is ModelSource.ContentUri) {
                            modelInfo.copy(name = displayName, path = modelPath)
                        } else {
                            modelInfo
                        }
                        _currentModel.value = publicInfo
                        _state.value = EngineState.ModelLoaded(publicInfo)
                        deferred.complete(Result.success(publicInfo))
                    } else {
                        _currentModel.value = null
                        lastLoadedSource = null
                        lastLoadedConfig = null
                        _state.value = EngineState.Ready
                        deferred.complete(Result.failure(IllegalStateException("Empty model info returned")))
                    }
                }

                override fun onModelLoadFailed(errorMessage: String?) {
                    if (!pendingModelLoad.compareAndSet(pending, null)) return
                    _modelLoadProgress.value = null
                    val msg = errorMessage ?: "Model loading failed"
                    _state.value = EngineState.Error(msg)
                    deferred.complete(Result.failure(IllegalStateException(msg)))
                }

                override fun onModelLoadProgress(progressPercent: Int) {
                    if (pendingModelLoad.get() !== pending) return
                    val percent = progressPercent.coerceIn(0, 99)
                    _modelLoadProgress.value = percent
                    _modelLoadProgressEvents.tryEmit(percent)
                }

                override fun onStarted(requestId: String?, promptMessageCount: Int) {}
                override fun onToken(requestId: String?, token: String?, tokenIndex: Int) {}
                override fun onProgress(requestId: String?, tokensGenerated: Int, elapsedMs: Long) {}
                override fun onPromptProgress(requestId: String?, tokensProcessed: Int, totalPromptTokens: Int, elapsedMs: Long) {}
                override fun onCompleted(requestId: String?, result: InferenceResult?) {}
                override fun onCancelled(requestId: String?, partialText: String?, tokensGenerated: Int) {}
                override fun onError(requestId: String?, errorMessage: String?, partialText: String?) {}
            }

            try {
                service.loadModel(
                    modelPath,
                    openedDescriptor,
                    displayName,
                    config,
                    callback
                )
            } finally {
                // Binder duplicates the descriptor into :inference. Release this
                // process' copy; the service retains its own descriptor for mmap.
                runCatching { openedDescriptor.close() }
                if (modelDescriptor === openedDescriptor) modelDescriptor = null
            }

            return try {
                withTimeout(effectiveTimeout.modelLoadMs) {
                    deferred.await()
                }
            } catch (tce: TimeoutCancellationException) {
                val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L
                Log.e(TAG, "Model loading timed out after ${elapsedMs}ms ($effectiveTimeout)")
                // Model loading has no inference request ID. Abort the isolated process
                // explicitly only after the model-load deadline expires.
                abortRemoteProcess(
                    requestId = null,
                    reason = "model_load_timeout",
                    targetPidOverride = targetPid
                )
                _modelLoadProgress.value = null
                val timeoutEx = InferenceTimeoutException(FinishReason.TIMEOUT_MODEL_LOAD, elapsedMs)
                _state.value = EngineState.Error(timeoutEx.message ?: "Model load timed out", timeoutEx)
                Result.failure(timeoutEx)
            } finally {
                pendingModelLoad.compareAndSet(pending, null)
            }
        } catch (t: Throwable) {
            registeredPendingLoad?.let { pendingModelLoad.compareAndSet(it, null) }
            runCatching { modelDescriptor?.close() }
            _state.value = EngineState.Error(t.message ?: "Failed to initiate model load")
            return Result.failure(t)
        }
    }

    override suspend fun unloadModel(): Result<Unit> = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val effective = lastLoadedConfig?.timeoutConfig ?: timeoutConfig
            val deferred = CompletableDeferred<Result<Unit>>()
            val targetPid = _remotePid.value
            try {
                val service = getService(effective)
                service.unloadModel(object : IInferenceCallback.Stub() {
                    override fun onModelLoaded(modelInfo: ModelInfo?) {
                        _currentModel.value = null
                        lastLoadedSource = null
                        lastLoadedConfig = null
                        _modelLoadProgress.value = null
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
                    override fun onPromptProgress(requestId: String?, tokensProcessed: Int, totalPromptTokens: Int, elapsedMs: Long) {}
                    override fun onCompleted(requestId: String?, result: InferenceResult?) {}
                    override fun onCancelled(requestId: String?, partialText: String?, tokensGenerated: Int) {}
                    override fun onError(requestId: String?, errorMessage: String?, partialText: String?) {}
                })
                withTimeout(effective.modelLoadMs) { deferred.await() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: TimeoutCancellationException) {
                val error = InferenceTimeoutException(
                    FinishReason.TIMEOUT_MODEL_LOAD,
                    effective.modelLoadMs
                )
                EngineDiagnostics.error("ipc_client", "MODEL_UNLOAD_TIMEOUT", error)
                _modelLoadProgress.value = null
                if (targetPid != null) abortRemoteProcess(null, "model_unload_timeout", targetPid)
                Result.failure(error)
            } catch (error: Throwable) {
                EngineDiagnostics.error("ipc_client", "MODEL_UNLOAD_FAILED", error)
                Result.failure(error)
            }
        }
    }

    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val currentModelInfo = _currentModel.value
        val requestId = UUID.randomUUID().toString()
        if (!activeRequestId.compareAndSet(null, requestId)) {
            trySend(InferenceEvent.Error(IllegalStateException("Another inference request is already active")))
            channel.close()
            return@callbackFlow
        }
        _state.value = EngineState.Inferring(request)

        val effectiveTimeout = request.timeoutConfig
            ?: lastLoadedConfig?.timeoutConfig
            ?: timeoutConfig

        val startNano = System.nanoTime()
        val prefillCompletedNano = AtomicLong(0L)
        val lastTokenNano = AtomicLong(startNano)
        val receivedFirstToken = AtomicBoolean(false)
        val receivedStarted = AtomicBoolean(false)
        val tokenCount = AtomicInteger(0)
        val promptTokensProcessed = AtomicInteger(0)
        val promptTokensTotal = AtomicInteger(0)
        val promptDecodeStartNano = AtomicLong(0L)
        val lastUiProgressNano = AtomicLong(startNano)
        val streamBuilder = StringBuilder()
        val terminalSent = AtomicBoolean(false)
        var watchdogJob: Job? = null
        var streamControl: ActiveStreamControl? = null

        data class TimeoutSnapshot(val reason: FinishReason, val elapsedMs: Long)

        val pendingTimeout = AtomicReference<TimeoutSnapshot?>(null)
        val timeoutReported = AtomicBoolean(false)

        fun finishStream(event: InferenceEvent): Boolean {
            if (!terminalSent.compareAndSet(false, true)) return false
            watchdogJob?.cancel()
            activeRequestId.compareAndSet(requestId, null)
            streamControl?.let { activeStreamControl.compareAndSet(it, null) }
            trySend(event)
            channel.close()
            return true
        }

        fun reportTimeout(): Boolean {
            val timeout = pendingTimeout.get() ?: return false
            if (!timeoutReported.compareAndSet(false, true)) return true
            val partial = synchronized(streamBuilder) { streamBuilder.toString() }
            // A timeout is a failure, not a successful completion carrying a TIMEOUT finish reason.
            finishStream(InferenceEvent.Error(
                InferenceTimeoutException(timeout.reason, timeout.elapsedMs),
                partial
            ))
            return true
        }

        fun completeTimeoutIfPending(): Boolean {
            if (pendingTimeout.get() == null) return false
            reportTimeout()
            return true
        }

        fun triggerTimeout(reason: FinishReason, elapsedMs: Long) {
            if (activeRequestId.get() != requestId ||
                !pendingTimeout.compareAndSet(null, TimeoutSnapshot(reason, elapsedMs))
            ) return

            EngineDiagnostics.error("ipc_client", "INFERENCE_TIMEOUT", InferenceTimeoutException(reason, elapsedMs))
            val targetPid = _remotePid.value
            val service = remoteService
            val serviceMatchesTarget = service != null &&
                (targetPid == null || runCatching { service.processId == targetPid }.getOrDefault(false))

            // Cooperative-first: signal the native cancellation flag and keep the request ID
            // active while waiting for the service to report that native execution has unwound.
            if (serviceMatchesTarget) {
                runCatching { service?.cancelInference(requestId) }
                    .onSuccess {
                        EngineDiagnostics.info(
                            "ipc_client",
                            "TIMEOUT_COOPERATIVE_CANCEL_SENT",
                            "reason=$reason, request=$requestId, remotePid=$targetPid"
                        )
                    }
                    .onFailure {
                        EngineDiagnostics.error("ipc_client", "TIMEOUT_COOPERATIVE_CANCEL_FAILED", it)
                    }
            }

            val graceMs = effectiveTimeout.abortGracePeriodMs
            clientScope.launch {
                val deadline = System.nanoTime() + graceMs * 1_000_000L
                while (activeRequestId.get() == requestId && System.nanoTime() < deadline) {
                    delay(KILL_CHECK_INTERVAL_MS)
                }

                if (activeRequestId.compareAndSet(requestId, null)) {
                    _state.value = EngineState.Reconnecting
                    EngineDiagnostics.info(
                        "ipc_client",
                        "TIMEOUT_COOPERATIVE_CANCEL_ESCALATED",
                        "reason=$reason, request=$requestId did not unwind within ${graceMs}ms; killing original PID=$targetPid"
                    )
                    abortRemoteProcess(
                        requestId = requestId,
                        reason = "inference_timeout_${reason}_cooperative_cancel_timeout",
                        targetPidOverride = targetPid
                    )
                } else {
                    EngineDiagnostics.info(
                        "ipc_client",
                        "TIMEOUT_COOPERATIVE_CANCEL_ACKNOWLEDGED",
                        "reason=$reason, request=$requestId completed cooperative unwind within ${graceMs}ms"
                    )
                }
                reportTimeout()
            }
        }

        // Tiered monotonic watchdog using System.nanoTime()
        watchdogJob = launch {
            while (isActive && activeRequestId.get() == requestId && pendingTimeout.get() == null) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                val now = System.nanoTime()
                val elapsedTotalMs = (now - startNano) / 1_000_000L

                // 1. Total inference timeout check
                if (elapsedTotalMs > effectiveTimeout.totalInferenceMs) {
                    triggerTimeout(FinishReason.TIMEOUT_TOTAL_INFERENCE, elapsedTotalMs)
                    break
                }

                // Prefill ends only when the native service reports successful prompt evaluation.
                if (!receivedStarted.get()) {
                    val elapsedPrefillMs = (now - startNano) / 1_000_000L
                    if (elapsedPrefillMs > effectiveTimeout.prefillMs) {
                        triggerTimeout(FinishReason.TIMEOUT_PREFILL, elapsedPrefillMs)
                        break
                    }
                } else if (!receivedFirstToken.get()) {
                    // First-token latency is measured from the end of prefill.
                    val phaseStart = prefillCompletedNano.get()
                    val elapsedFirstTokenMs = if (phaseStart > 0L) {
                        (now - phaseStart) / 1_000_000L
                    } else 0L
                    if (elapsedFirstTokenMs > effectiveTimeout.firstTokenMs) {
                        triggerTimeout(FinishReason.TIMEOUT_FIRST_TOKEN, elapsedFirstTokenMs)
                        break
                    }
                } else {
                    val elapsedInterTokenMs = (now - lastTokenNano.get()) / 1_000_000L
                    if (elapsedInterTokenMs > effectiveTimeout.interTokenMs) {
                        triggerTimeout(FinishReason.TIMEOUT_INTER_TOKEN, elapsedInterTokenMs)
                        break
                    }
                }

                // Keep elapsed time visible at 1 Hz. This is a status heartbeat, not
                // fabricated progress: token counts only change on actual engine callbacks.
                if (now - lastUiProgressNano.get() >= 1_000_000_000L) {
                    val generated = tokenCount.get()
                    val progress = when {
                        !receivedStarted.get() -> {
                            val processed = promptTokensProcessed.get()
                            val total = promptTokensTotal.get()
                            val speed = if (elapsedTotalMs > 0L) processed * 1000.0 / elapsedTotalMs else 0.0
                            val eta = if (speed > 0.0 && total > processed) {
                                (((total - processed) * 1000.0) / speed).toLong()
                            } else null
                            InferenceEvent.Progress(
                                tokensGenerated = 0,
                                elapsedMs = elapsedTotalMs,
                                phase = if (total > 0) ProgressPhase.PROMPT_EVALUATION else ProgressPhase.PROMPT_PREPARATION,
                                promptTokensProcessed = processed,
                                promptTokensTotal = total,
                                tokensPerSecond = speed,
                                estimatedRemainingMs = eta
                            )
                        }
                        !receivedFirstToken.get() -> InferenceEvent.Progress(
                            tokensGenerated = 0,
                            elapsedMs = ((now - prefillCompletedNano.get()) / 1_000_000L).coerceAtLeast(0L),
                            phase = ProgressPhase.WAITING_FOR_FIRST_TOKEN,
                            promptTokensProcessed = promptTokensProcessed.get(),
                            promptTokensTotal = promptTokensTotal.get()
                        )
                        else -> {
                            val elapsed = ((now - prefillCompletedNano.get()) / 1_000_000L).coerceAtLeast(0L)
                            val speed = if (elapsed > 0L) generated * 1000.0 / elapsed else 0.0
                            val eta = if (speed > 0.0 && request.maxPredictTokens > generated) {
                                (((request.maxPredictTokens - generated) * 1000.0) / speed).toLong()
                            } else null
                            InferenceEvent.Progress(
                                tokensGenerated = generated,
                                elapsedMs = elapsed,
                                phase = ProgressPhase.GENERATING,
                                tokensPerSecond = speed,
                                estimatedRemainingMs = eta
                            )
                        }
                    }
                    trySend(progress)
                    lastUiProgressNano.set(now)
                }
            }
        }

        val control = ActiveStreamControl(
            requestId = requestId,
            cancelAfterAbort = {
                if (pendingTimeout.get() != null) {
                    reportTimeout()
                } else {
                    _state.value = EngineState.Reconnecting
                    val partial = synchronized(streamBuilder) { streamBuilder.toString() }
                    finishStream(InferenceEvent.Cancelled(partial, tokenCount.get()))
                }
            },
            failAfterDisconnect = { error ->
                if (pendingTimeout.get() != null) {
                    reportTimeout()
                } else {
                    val partial = synchronized(streamBuilder) { streamBuilder.toString() }
                    finishStream(InferenceEvent.Error(error, partial))
                }
            }
        )
        streamControl = control
        activeStreamControl.set(control)

        val callback = object : IInferenceCallback.Stub() {
            override fun onStarted(reqId: String?, promptMessageCount: Int) {
                if (reqId == activeRequestId.get() && receivedStarted.compareAndSet(false, true)) {
                    val completedAt = System.nanoTime()
                    prefillCompletedNano.set(completedAt)
                    val elapsedMs = (completedAt - startNano) / 1_000_000L
                    EngineDiagnostics.info(
                        "ipc_client",
                        "PREFILL_COMPLETED",
                        "request=$reqId, messages=$promptMessageCount, elapsedMs=$elapsedMs"
                    )
                    trySend(InferenceEvent.Started(promptMessageCount))
                    trySend(
                        InferenceEvent.Progress(
                            tokensGenerated = 0,
                            elapsedMs = 0L,
                            phase = ProgressPhase.WAITING_FOR_FIRST_TOKEN,
                            promptTokensProcessed = promptTokensProcessed.get(),
                            promptTokensTotal = promptTokensTotal.get()
                        )
                    )
                    lastUiProgressNano.set(completedAt)
                }
            }

            override fun onToken(reqId: String?, token: String?, tokenIndex: Int) {
                if (reqId == activeRequestId.get() && !token.isNullOrEmpty()) {
                    val receivedAt = System.nanoTime()
                    lastTokenNano.set(receivedAt)
                    val isFirstToken = receivedFirstToken.compareAndSet(false, true)
                    val generated = tokenCount.incrementAndGet()
                    synchronized(streamBuilder) { streamBuilder.append(token) }
                    if (isFirstToken) {
                        val elapsedMs = (receivedAt - prefillCompletedNano.get()) / 1_000_000L
                        EngineDiagnostics.info(
                            "ipc_client",
                            "FIRST_TOKEN_RECEIVED",
                            "request=$reqId, latencyAfterPrefillMs=$elapsedMs"
                        )
                    }
                    trySend(InferenceEvent.Token(token, tokenIndex))
                    val elapsedMs = ((receivedAt - prefillCompletedNano.get()) / 1_000_000L).coerceAtLeast(0L)
                    val speed = if (elapsedMs > 0L) generated * 1000.0 / elapsedMs else 0.0
                    val eta = if (speed > 0.0 && request.maxPredictTokens > generated) {
                        (((request.maxPredictTokens - generated) * 1000.0) / speed).toLong()
                    } else null
                    trySend(
                        InferenceEvent.Progress(
                            tokensGenerated = generated,
                            elapsedMs = elapsedMs,
                            phase = ProgressPhase.GENERATING,
                            tokensPerSecond = speed,
                            estimatedRemainingMs = eta
                        )
                    )
                    lastUiProgressNano.set(receivedAt)
                }
            }

            override fun onPromptProgress(
                reqId: String?,
                tokensProcessed: Int,
                totalPromptTokens: Int,
                elapsedMs: Long
            ) {
                if (reqId == activeRequestId.get()) {
                    val processed = tokensProcessed.coerceAtLeast(0)
                    val total = totalPromptTokens.coerceAtLeast(0)
                    if (processed == 0) promptDecodeStartNano.set(System.nanoTime())
                    promptTokensProcessed.set(processed)
                    promptTokensTotal.set(total)
                    val decodeStart = promptDecodeStartNano.get()
                    val measuredElapsedMs = if (decodeStart > 0L) {
                        ((System.nanoTime() - decodeStart) / 1_000_000L).coerceAtLeast(1L)
                    } else elapsedMs.coerceAtLeast(1L)
                    val speed = processed * 1000.0 / measuredElapsedMs
                    val eta = if (speed > 0.0 && total > processed) {
                        (((total - processed) * 1000.0) / speed).toLong()
                    } else null
                    trySend(
                        InferenceEvent.Progress(
                            tokensGenerated = 0,
                            elapsedMs = measuredElapsedMs,
                            phase = ProgressPhase.PROMPT_EVALUATION,
                            promptTokensProcessed = processed,
                            promptTokensTotal = total,
                            tokensPerSecond = speed,
                            estimatedRemainingMs = eta
                        )
                    )
                    lastUiProgressNano.set(System.nanoTime())
                }
            }

            override fun onProgress(reqId: String?, tokensGenerated: Int, elapsedMs: Long) {
                if (reqId == activeRequestId.get()) {
                    val generated = tokensGenerated.coerceAtLeast(0)
                    val elapsed = elapsedMs.coerceAtLeast(0L)
                    val speed = if (elapsed > 0L) generated * 1000.0 / elapsed else 0.0
                    val eta = if (speed > 0.0 && request.maxPredictTokens > generated) {
                        (((request.maxPredictTokens - generated) * 1000.0) / speed).toLong()
                    } else null
                    trySend(
                        InferenceEvent.Progress(
                            tokensGenerated = generated,
                            elapsedMs = elapsed,
                            phase = ProgressPhase.GENERATING,
                            tokensPerSecond = speed,
                            estimatedRemainingMs = eta
                        )
                    )
                    lastUiProgressNano.set(System.nanoTime())
                }
            }

            override fun onCompleted(reqId: String?, result: InferenceResult?) {
                if (reqId == activeRequestId.get()) {
                    if (completeTimeoutIfPending()) return
                    if (result == null) {
                        finishStream(InferenceEvent.Error(
                            IllegalStateException("Inference service completed request $reqId without a result"),
                            synchronized(streamBuilder) { streamBuilder.toString() }
                        ))
                    } else {
                        finishStream(InferenceEvent.Completed(result))
                    }
                }
            }

            override fun onCancelled(reqId: String?, partialText: String?, tokensGenerated: Int) {
                if (reqId == activeRequestId.get()) {
                    if (completeTimeoutIfPending()) return
                    val localPartial = synchronized(streamBuilder) { streamBuilder.toString() }
                    finishStream(
                        InferenceEvent.Cancelled(
                            partialText?.takeIf { it.isNotEmpty() } ?: localPartial,
                            maxOf(tokensGenerated, tokenCount.get())
                        )
                    )
                }
            }

            override fun onError(reqId: String?, errorMessage: String?, partialText: String?) {
                if (reqId == activeRequestId.get()) {
                    if (completeTimeoutIfPending()) return
                    val error = IllegalStateException(errorMessage ?: "Unknown inference error")
                    EngineDiagnostics.error("ipc_client", "REMOTE_INFERENCE_ERROR", error)
                    val localPartial = synchronized(streamBuilder) { streamBuilder.toString() }
                    finishStream(InferenceEvent.Error(
                        error,
                        partialText?.takeIf { it.isNotEmpty() } ?: localPartial
                    ))
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
            val partial = synchronized(streamBuilder) { streamBuilder.toString() }
            finishStream(InferenceEvent.Error(e, partial))
        }

        awaitClose {
            watchdogJob?.cancel()
            streamControl?.let { activeStreamControl.compareAndSet(it, null) }
            if (activeRequestId.compareAndSet(requestId, null)) {
                val service = remoteService
                clientScope.launch(Dispatchers.IO) {
                    runCatching { service?.cancelInference(requestId) }
                }
            }
            // A disconnect/abort may already have moved the state to Reconnecting or Error.
            // Never overwrite that newer lifecycle state with the model snapshot captured
            // when this stream began.
            if (_state.value is EngineState.Inferring) {
                if (currentModelInfo != null) {
                    _state.value = EngineState.ModelLoaded(currentModelInfo)
                } else {
                    _state.value = EngineState.Ready
                }
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
        val requestId = activeRequestId.get()
        if (requestId == null) {
            // An idle cancel must not tear down :inference or discard an already loaded model.
            EngineDiagnostics.info(
                "ipc_client",
                "CANCEL_IGNORED_NO_ACTIVE_REQUEST",
                "No active inference request; remote PID=${_remotePid.value} remains running"
            )
            return
        }

        val targetPid = _remotePid.value
        EngineDiagnostics.info(
            "ipc_client",
            "CANCEL_REQUESTED",
            "Cooperative cancellation requested for request=$requestId, remotePid=$targetPid"
        )
        val service = remoteService
        // Binder calls (including processId) are never made on the caller/UI thread.
        clientScope.launch(Dispatchers.IO) {
            val matchingService = service != null &&
                (targetPid == null || runCatching { service.processId == targetPid }.getOrDefault(false))
            if (matchingService) {
                runCatching { service?.cancelInference(requestId) }
                    .onFailure { EngineDiagnostics.error("ipc_client", "COOPERATIVE_CANCEL_FAILED", it) }
            }
        }

        // If the service acknowledges cancellation, its onCancelled callback completes
        // the stream with the partial text. Otherwise the client kills the original PID
        // and also completes the local stream so generateChat cannot wait forever.
        val graceMs = (lastLoadedConfig?.timeoutConfig ?: timeoutConfig).abortGracePeriodMs
        clientScope.launch {
            delay(graceMs)
            if (activeRequestId.compareAndSet(requestId, null)) {
                _state.value = EngineState.Reconnecting
                EngineDiagnostics.info(
                    "ipc_client",
                    "COOPERATIVE_CANCEL_ESCALATED",
                    "request=$requestId did not finish within ${graceMs}ms; aborting original PID=$targetPid"
                )
                activeStreamControl.get()
                    ?.takeIf { it.requestId == requestId }
                    ?.cancelAfterAbort?.invoke()
                abortRemoteProcess(
                    requestId = requestId,
                    reason = "cooperative_cancel_timeout",
                    targetPidOverride = targetPid
                )
            }
        }
    }

    /**
     * Terminates the isolated process only after a real abort condition.
     *
     * requestId may be null for operations such as model loading, which have no inference
     * request ID but have independently exceeded their configured timeout.
     */
    private fun abortRemoteProcess(
        requestId: String?,
        reason: String,
        targetPidOverride: Int? = _remotePid.value
    ) {
        val targetPid = targetPidOverride
        val service = remoteService
        val servicePid = runCatching { service?.processId }.getOrNull()
        val serviceMatchesTarget = service != null &&
            (targetPid == null || servicePid == targetPid)
        val effective = lastLoadedConfig?.timeoutConfig ?: timeoutConfig

        if (serviceMatchesTarget && targetPid != null) {
            // Never send a kill request to a replacement process for an older operation.
            // One-way Binder call: it must not wait for native cleanup in the service process.
            try {
                service?.killProcess()
            } catch (e: Exception) {
                Log.w(TAG, "Remote inference process may already have exited", e)
            }
        } else if (serviceMatchesTarget && service != null && requestId != null) {
            try {
                service.cancelInference(requestId)
            } catch (ignored: Exception) {}
        } else if (service != null && targetPid != null) {
            EngineDiagnostics.info(
                "ipc_client",
                "PROCESS_ABORT_SKIPPED_REPLACEMENT",
                "reason=$reason, targetPid=$targetPid, currentServicePid=$servicePid"
            )
        }

        EngineDiagnostics.info(
            "ipc_client",
            "PROCESS_ABORT_REQUESTED",
            "reason=$reason, request=$requestId, remotePid=$targetPid"
        )

        if (targetPid != null && targetPid != Process.myPid()) {
            clientScope.launch {
                var isAlive = isProcessAlive(targetPid)
                val gracefulDeadline = System.currentTimeMillis() + effective.abortGracePeriodMs

                while (isAlive && System.currentTimeMillis() < gracefulDeadline) {
                    delay(KILL_CHECK_INTERVAL_MS)
                    isAlive = isProcessAlive(targetPid)
                }

                if (isAlive) {
                    Log.w(TAG, "Inference PID=$targetPid survived self-termination grace period; issuing client-side kill")
                    EngineDiagnostics.info("ipc_client", "HARD_KILL", "Client-side kill for inference PID=$targetPid")
                    try {
                        Process.killProcess(targetPid)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed client-side kill of inference PID=$targetPid", e)
                    }

                    val hardKillDeadline = System.currentTimeMillis() + effective.abortGracePeriodMs
                    while (isAlive && System.currentTimeMillis() < hardKillDeadline) {
                        delay(KILL_CHECK_INTERVAL_MS)
                        isAlive = isProcessAlive(targetPid)
                    }
                }

                if (!isAlive) {
                    EngineDiagnostics.info("ipc_client", "PROCESS_ABORT_CONFIRMED", "Inference PID=$targetPid is no longer alive")
                    if (mockService == null && !isClosed.get() && _remotePid.value == targetPid) {
                        _state.value = EngineState.Reconnecting
                        triggerReconnect()
                    }
                } else {
                    val error = IllegalStateException(
                        "Could not confirm termination of inference process PID=$targetPid after self-kill and client-side kill"
                    )
                    EngineDiagnostics.error("ipc_client", "PROCESS_ABORT_UNCONFIRMED", error)
                    _state.value = EngineState.Error(error.message ?: "Inference process termination could not be confirmed", error)
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

