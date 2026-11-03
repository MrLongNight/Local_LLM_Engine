package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceResult
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class LocalLlmEngineImpl(
    private val context: Context
) : LocalLlmEngine {

    companion object {
        private const val TAG = "LocalLlmEngineImpl"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private val _state = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    override val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _backendInfo = MutableStateFlow(BackendInfo())
    override val backendInfo: StateFlow<BackendInfo> = _backendInfo.asStateFlow()

    private val engineHandle = AtomicLong(0L)
    private val isCancelled = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val nativeHandleLock = Any()

    init {
        scope.launch {
            initialize()
        }
    }

    override suspend fun initialize(): Result<Unit> = withContext(llamaDispatcher) {
        runCatching {
            check(!isClosed.get()) { "Engine is closed" }
            if (engineHandle.get() != 0L &&
                (_state.value is EngineState.Ready || _state.value is EngineState.ModelLoaded)
            ) return@runCatching
            _state.value = EngineState.Initializing

            val loaded = NativeEngineHandle.ensureLoaded()
            if (!loaded) {
                throw IllegalStateException("Failed to load native library localllm_engine")
            }

            val handle = NativeEngineHandle.nativeCreateEngine()
            if (handle == 0L) {
                throw IllegalStateException("Failed to allocate native EngineInstance")
            }
            engineHandle.set(handle)

            val sysInfo = runCatching { NativeEngineHandle.nativeGetSystemInfo() }.getOrDefault("")
            val recThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val hasVulkanBackend = NativeEngineHandle.nativeIsVulkanBackendAvailable()
            _backendInfo.value = BackendInfo(
                backendName = if (hasVulkanBackend) {
                    "llama.cpp (Vulkan GPU + CPU)"
                } else {
                    "llama.cpp (CPU; Vulkan-Backend nicht in diesem Build enthalten)"
                },
                systemInfo = sysInfo,
                isAvailable = true,
                recommendedThreads = recThreads
            )

            if (!isClosed.get()) {
                _state.value = EngineState.Ready
                Log.i(TAG, "LocalLlmEngine initialized successfully with handle: $handle")
            }
        }.onFailure { err ->
            Log.e(TAG, "Initialization failed", err)
            if (!isClosed.get()) {
                _state.value = EngineState.Error("Failed to initialize engine: ${err.message}", err)
            }
        }
    }

    override suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> = withContext(llamaDispatcher) {
        runCatching {
            val handle = getValidHandle()
            _currentModel.value = null
            _state.value = EngineState.LoadingModel(source)

            val file: File = ModelSourceResolver.resolveToFile(context, source)
            Log.i(TAG, "Loading model file: ${file.absolutePath} (${file.length()} bytes)")

            val ret = NativeEngineHandle.nativeLoadModel(
                handle = handle,
                modelPath = file.absolutePath,
                contextSize = config.contextSize,
                batchSize = config.batchSize,
                threads = config.threads,
                temperature = config.temperature,
                topP = config.topP,
                backendMode = config.backendPreference.modeId,
                gpuLayers = config.gpuLayers
            )

            when (ret) {
                0 -> { /* Success */ }
                101 -> throw IllegalStateException(
                    "Vulkan backend was explicitly requested, but no Vulkan GPU device is available or initialization failed."
                )
                else -> throw IllegalStateException("Native model load failed with error code: $ret")
            }

            val rawMetadata = NativeEngineHandle.nativeGetModelMetadata(handle)
            val parts = rawMetadata.split(";")
            val desc = parts.getOrNull(0) ?: ""
            val nParams = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val nCtxTrain = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull() ?: file.length()
            val activeBackend = parts.getOrNull(4) ?: NativeEngineHandle.nativeGetActiveBackend(handle)
            val architecture = NativeEngineHandle.nativeGetModelArchitecture(handle)
                .ifBlank { "unknown" }

            val sha256 = calculateSha256(file)

            val info = ModelInfo(
                name = file.name,
                path = file.absolutePath,
                sizeBytes = sizeBytes,
                parameterCount = nParams,
                contextLengthTrain = nCtxTrain,
                architecture = architecture,
                description = desc,
                activeBackend = activeBackend,
                sha256 = sha256
            )

            if (!isClosed.get()) {
                _currentModel.value = info
                _state.value = EngineState.ModelLoaded(info)
                Log.i(TAG, "Model loaded successfully: $info")
            }
            info
        }.onFailure { err ->
            Log.e(TAG, "Model load failed", err)
            if (!isClosed.get()) {
                _state.value = EngineState.Error("Failed to load model: ${err.message}", err)
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> = withContext(llamaDispatcher) {
        runCatching {
            check(!isClosed.get()) { "Engine is closed" }
            val handle = engineHandle.get()
            if (handle != 0L) {
                NativeEngineHandle.nativeUnloadModel(handle)
            }
            _currentModel.value = null
            _state.value = EngineState.Ready
            Log.i(TAG, "Model unloaded successfully")
            Unit
        }
    }

    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = flow {
        val modelInfo = _currentModel.value
            ?: throw IllegalStateException("No model loaded. Call loadModel() before chat.")
        val handle = getValidHandle()
        _state.value = EngineState.Inferring(request)
        isCancelled.set(false)
        NativeEngineHandle.nativeResetCancellation(handle)

        val outputBuilder = StringBuilder()
        var tokenEventIndex = 0
        try {
            emit(InferenceEvent.Started(request.messages.size))
            val roles = request.messages.map { it.role.roleName }.toTypedArray()
            val contents = request.messages.map { it.content }.toTypedArray()

            // Native prompt evaluation must not clear a cancel request arriving after reset.
            val processRes = NativeEngineHandle.nativeProcessChat(
                handle = handle, roles = roles, contents = contents, maxPredict = request.maxPredictTokens
            )
            if (processRes == -2 || isCancelled.get()) {
                emit(InferenceEvent.Cancelled("", 0))
                return@flow
            }
            if (processRes != 0) {
                emit(InferenceEvent.Error(
                    IllegalStateException("Native prompt evaluation failed with code $processRes")
                ))
                return@flow
            }

            // Native code enforces maxPredictTokens; UTF-8 chunks are not model-token counts.
            while (!isCancelled.get()) {
                val tokenPiece = NativeEngineHandle.nativeGenerateNextToken(handle) ?: break
                if (tokenPiece.isNotEmpty()) {
                    outputBuilder.append(tokenPiece)
                    emit(InferenceEvent.Token(tokenPiece, tokenEventIndex++))
                }
            }

            val rawReason = NativeEngineHandle.nativeGetLastFinishReason(handle)
            val finishReason = when {
                isCancelled.get() -> FinishReason.CANCELLED
                rawReason == 0 -> FinishReason.STOP
                rawReason == 1 -> FinishReason.MAX_TOKENS
                rawReason == 2 -> FinishReason.CANCELLED
                else -> FinishReason.ERROR
            }
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(0)
            val durationMs = (NativeEngineHandle.nativeGetInferenceDurationUs(handle) / 1000L).coerceAtLeast(1L)
            val tokensPerSec = actualTokens * 1000.0 / durationMs
            val fullText = outputBuilder.toString()

            when (finishReason) {
                FinishReason.CANCELLED -> emit(InferenceEvent.Cancelled(fullText, actualTokens))
                FinishReason.ERROR -> emit(InferenceEvent.Error(
                    IllegalStateException("Native generation ended with finish reason $rawReason"), fullText
                ))
                FinishReason.STOP, FinishReason.MAX_TOKENS -> emit(InferenceEvent.Completed(
                    InferenceResult(fullText, actualTokens, durationMs, tokensPerSec, finishReason)
                ))
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "streamChat coroutine cancelled; notifying native engine")
            isCancelled.set(true)
            NativeEngineHandle.nativeCancel(handle)
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error during inference", t)
            emit(InferenceEvent.Error(t, outputBuilder.toString()))
        } finally {
            isCancelled.set(false)
            if (!isClosed.get()) _state.value = EngineState.ModelLoaded(modelInfo)
        }
    }.flowOn(llamaDispatcher)

    override suspend fun generateChat(request: InferenceRequest): Result<InferenceResult> {
        return runCatching {
            var completedResult: InferenceResult? = null
            var lastError: Throwable? = null

            streamChat(request).collect { event ->
                when (event) {
                    is InferenceEvent.Completed -> completedResult = event.result
                    is InferenceEvent.Cancelled -> {
                        completedResult = InferenceResult(
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

            completedResult ?: throw (lastError ?: IllegalStateException("Generation did not produce a result"))
        }
    }

    override fun cancelInference() {
        isCancelled.set(true)
        synchronized(nativeHandleLock) {
            if (!isClosed.get()) {
                val handle = engineHandle.get()
                if (handle != 0L) NativeEngineHandle.nativeCancel(handle)
            }
        }
    }

    override fun close() {
        val shouldClose = synchronized(nativeHandleLock) {
            if (!isClosed.compareAndSet(false, true)) false
            else {
                isCancelled.set(true)
                val handle = engineHandle.get()
                if (handle != 0L) NativeEngineHandle.nativeCancel(handle)
                true
            }
        }
        if (!shouldClose) return
        _state.value = EngineState.Closed
        // Defer pointer destruction until the single engine dispatcher has left any JNI call.
        scope.launch {
            synchronized(nativeHandleLock) {
                val handle = engineHandle.getAndSet(0L)
                if (handle != 0L) NativeEngineHandle.nativeDestroyEngine(handle)
            }
            scope.cancel()
            Log.i(TAG, "LocalLlmEngine closed")
        }
    }

    private fun calculateSha256(file: File): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("N/A")

    private fun getValidHandle(): Long {
        check(!isClosed.get()) { "Engine is closed" }
        val h = engineHandle.get()
        check(h != 0L) { "Engine handle is invalid or closed" }
        return h
    }
}
