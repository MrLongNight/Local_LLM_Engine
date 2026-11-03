package com.mydocanizer.llm.engine.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
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
import com.mydocanizer.llm.engine.internal.ModelSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Client-side IPC gateway connecting the main app process to the remote :inference process.
 *
 * Implements [LocalLlmEngine] for drop-in replacement.
 * Supports passing a mock [IInferenceService] directly for unit tests without launching an Android Service.
 */
class InferenceServiceClient(
    private val context: Context,
    private val mockService: IInferenceService? = null
) : LocalLlmEngine {

    companion object {
        private const val TAG = "InferenceServiceClient"
    }

    private val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    override val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _backendInfo = MutableStateFlow(BackendInfo())
    override val backendInfo: StateFlow<BackendInfo> = _backendInfo.asStateFlow()

    private val _remotePid = MutableStateFlow<Int?>(null)
    val remotePid: StateFlow<Int?> = _remotePid.asStateFlow()

    private var remoteService: IInferenceService? = mockService
    private var isBound = false
    private val serviceConnectionDeferred = CompletableDeferred<IInferenceService>()
    private var activeRequestId: String? = null
    private var lastRequestId: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IInferenceService.Stub.asInterface(binder)
            remoteService = service
            if (!serviceConnectionDeferred.isCompleted) {
                serviceConnectionDeferred.complete(service)
            }
            try {
                val pid = service.processId
                _remotePid.value = pid
                _backendInfo.value = service.backendInfo ?: BackendInfo()
                _state.value = EngineState.Ready
                EngineDiagnostics.info("ipc_client", "SERVICE_CONNECTED", "Remote service PID=$pid connected")
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to query connected service properties", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Error("Remote inference process terminated unexpectedly")
            EngineDiagnostics.error("ipc_client", "SERVICE_DISCONNECTED", IllegalStateException("Inference process killed or disconnected"))
        }

        override fun onBindingDied(name: ComponentName?) {
            remoteService = null
            _remotePid.value = null
            _currentModel.value = null
            _state.value = EngineState.Error("Inference process crashed (:inference died)")
            EngineDiagnostics.error("ipc_client", "BINDING_DIED", IllegalStateException("Inference process died"))
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

    private suspend fun getService(): IInferenceService {
        return remoteService ?: serviceConnectionDeferred.await()
    }

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val service = getService()
            service.initialize()
            _backendInfo.value = service.backendInfo ?: BackendInfo()
            _remotePid.value = service.processId
            _state.value = EngineState.Ready
        }
    }

    override suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Result<ModelInfo>>()
        try {
            _state.value = EngineState.LoadingModel(source)
            val file: File = ModelSourceResolver.resolveToFile(context, source)
            val service = getService()

            val callback = object : IInferenceCallback.Stub() {
                override fun onModelLoaded(modelInfo: ModelInfo?) {
                    if (modelInfo != null) {
                        _currentModel.value = modelInfo
                        _state.value = EngineState.ModelLoaded(modelInfo)
                        deferred.complete(Result.success(modelInfo))
                    } else {
                        _currentModel.value = null
                        _state.value = EngineState.Ready
                        deferred.complete(Result.failure(IllegalStateException("Empty model info returned")))
                    }
                }

                override fun onModelLoadFailed(errorMessage: String?) {
                    val msg = errorMessage ?: "Model loading failed"
                    _state.value = EngineState.Error(msg)
                    deferred.complete(Result.failure(IllegalStateException(msg)))
                }

                override fun onStarted(requestId: String?, promptMessageCount: Int) {}
                override fun onToken(requestId: String?, token: String?, tokenIndex: Int) {}
                override fun onProgress(requestId: String?, tokensGenerated: Int, elapsedMs: Long) {}
                override fun onCompleted(requestId: String?, result: InferenceResult?) {}
                override fun onCancelled(requestId: String?, partialText: String?, tokensGenerated: Int) {}
                override fun onError(requestId: String?, errorMessage: String?, partialText: String?) {}
            }

            service.loadModel(file.absolutePath, config, callback)
        } catch (t: Throwable) {
            _state.value = EngineState.Error(t.message ?: "Failed to initiate model load")
            deferred.complete(Result.failure(t))
        }

        deferred.await()
    }

    override suspend fun unloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Result<Unit>>()
        try {
            val service = getService()
            service.unloadModel(object : IInferenceCallback.Stub() {
                override fun onModelLoaded(modelInfo: ModelInfo?) {
                    _currentModel.value = null
                    _state.value = EngineState.Ready
                    deferred.complete(Result.success(Unit))
                }

                override fun onModelLoadFailed(errorMessage: String?) {
                    deferred.complete(Result.failure(IllegalStateException(errorMessage ?: "Unload failed")))
                }

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

    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val currentModelInfo = _currentModel.value
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        lastRequestId = requestId
        _state.value = EngineState.Inferring(request)

        val callback = object : IInferenceCallback.Stub() {
            override fun onStarted(reqId: String?, promptMessageCount: Int) {
                if (reqId == requestId) {
                    trySend(InferenceEvent.Started(promptMessageCount))
                }
            }

            override fun onToken(reqId: String?, token: String?, tokenIndex: Int) {
                if (reqId == requestId && token != null) {
                    trySend(InferenceEvent.Token(token, tokenIndex))
                }
            }

            override fun onProgress(reqId: String?, tokensGenerated: Int, elapsedMs: Long) {
                if (reqId == requestId) {
                    trySend(InferenceEvent.Progress(tokensGenerated, elapsedMs))
                }
            }

            override fun onCompleted(reqId: String?, result: InferenceResult?) {
                if (reqId == requestId && result != null) {
                    trySend(InferenceEvent.Completed(result))
                    channel.close()
                }
            }

            override fun onCancelled(reqId: String?, partialText: String?, tokensGenerated: Int) {
                if (reqId == requestId) {
                    trySend(InferenceEvent.Cancelled(partialText ?: "", tokensGenerated))
                    channel.close()
                }
            }

            override fun onError(reqId: String?, errorMessage: String?, partialText: String?) {
                if (reqId == requestId) {
                    trySend(InferenceEvent.Error(
                        IllegalStateException(errorMessage ?: "Unknown inference error"),
                        partialText ?: ""
                    ))
                    channel.close()
                }
            }

            override fun onModelLoaded(modelInfo: ModelInfo?) {}
            override fun onModelLoadFailed(errorMessage: String?) {}
        }

        try {
            val service = getService()
            service.streamChat(requestId, request, callback)
        } catch (e: Exception) {
            trySend(InferenceEvent.Error(e))
            channel.close()
        }

        awaitClose {
            // Cooperative cancellation on coroutine close
            try {
                remoteService?.cancelInference(requestId)
            } catch (ignored: Exception) {}
            if (activeRequestId == requestId) {
                activeRequestId = null
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
        val reqId = activeRequestId ?: lastRequestId
        try {
            remoteService?.cancelInference(reqId)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting cancel", e)
        }
    }

    override fun close() {
        cancelInference()
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
