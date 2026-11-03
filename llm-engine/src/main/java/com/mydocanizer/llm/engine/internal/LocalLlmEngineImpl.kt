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

    init {
        scope.launch {
            initialize()
        }
    }

    override suspend fun initialize(): Result<Unit> = withContext(llamaDispatcher) {
        runCatching {
            if (_state.value is EngineState.Ready || _state.value is EngineState.ModelLoaded) {
                return@runCatching
            }
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
            _backendInfo.value = BackendInfo(
                backendName = "llama.cpp (Vulkan / ARM NEON / KleidiAI / AVX)",
                systemInfo = sysInfo,
                isAvailable = true,
                recommendedThreads = recThreads
            )

            _state.value = EngineState.Ready
            Log.i(TAG, "LocalLlmEngine initialized successfully with handle: $handle")
        }.onFailure { err ->
            Log.e(TAG, "Initialization failed", err)
            _state.value = EngineState.Error("Failed to initialize engine: ${err.message}", err)
        }
    }

    override suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> = withContext(llamaDispatcher) {
        runCatching {
            val handle = getValidHandle()
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

            val sha256 = calculateSha256(file)

            val info = ModelInfo(
                name = file.name,
                path = file.absolutePath,
                sizeBytes = sizeBytes,
                parameterCount = nParams,
                contextLengthTrain = nCtxTrain,
                architecture = "GGUF",
                description = desc,
                activeBackend = activeBackend,
                sha256 = sha256
            )

            _currentModel.value = info
            _state.value = EngineState.ModelLoaded(info)
            Log.i(TAG, "Model loaded successfully: $info")
            info
        }.onFailure { err ->
            Log.e(TAG, "Model load failed", err)
            _state.value = EngineState.Error("Failed to load model: ${err.message}", err)
        }
    }

    override suspend fun unloadModel(): Result<Unit> = withContext(llamaDispatcher) {
        runCatching {
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

        emit(InferenceEvent.Started(request.messages.size))

        val roles = request.messages.map { it.role.roleName }.toTypedArray()
        val contents = request.messages.map { it.content }.toTypedArray()

        val processRes = NativeEngineHandle.nativeProcessChat(
            handle = handle,
            roles = roles,
            contents = contents,
            maxPredict = request.maxPredictTokens
        )

        if (processRes != 0) {
            val err = IllegalStateException("Native prompt evaluation failed with code $processRes")
            emit(InferenceEvent.Error(err))
            _state.value = EngineState.ModelLoaded(modelInfo)
            return@flow
        }

        val outputBuilder = StringBuilder()
        var tokenCount = 0

        try {
            while (!isCancelled.get()) {
                val tokenPiece = NativeEngineHandle.nativeGenerateNextToken(handle)
                if (tokenPiece == null) {
                    break
                }
                if (tokenPiece.isNotEmpty()) {
                    outputBuilder.append(tokenPiece)
                    emit(InferenceEvent.Token(tokenPiece, tokenCount))
                    tokenCount++
                    if (tokenCount >= request.maxPredictTokens) {
                        break
                    }
                }
            }

            val rawReason = NativeEngineHandle.nativeGetLastFinishReason(handle)
            val finishReason = when {
                isCancelled.get() -> FinishReason.CANCELLED
                rawReason == 0 -> FinishReason.STOP
                rawReason == 1 -> FinishReason.MAX_TOKENS
                rawReason == 2 -> FinishReason.CANCELLED
                else -> FinishReason.STOP
            }

            val durationUs = NativeEngineHandle.nativeGetInferenceDurationUs(handle)
            val durationMs = (durationUs / 1000L).coerceAtLeast(1L)
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            val tokensPerSec = (actualTokens * 1000.0) / durationMs
            val fullText = outputBuilder.toString()

            if (finishReason == FinishReason.CANCELLED) {
                emit(InferenceEvent.Cancelled(fullText, actualTokens))
            } else {
                val result = InferenceResult(
                    text = fullText,
                    tokensGenerated = actualTokens,
                    durationMs = durationMs,
                    tokensPerSecond = tokensPerSec,
                    finishReason = finishReason
                )
                emit(InferenceEvent.Completed(result))
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "streamChat coroutine cancelled, notifying native engine")
            NativeEngineHandle.nativeCancel(handle)
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            emit(InferenceEvent.Cancelled(outputBuilder.toString(), actualTokens))
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error during generation loop", t)
            emit(InferenceEvent.Error(t, outputBuilder.toString()))
        } finally {
            isCancelled.set(false)
            _state.value = EngineState.ModelLoaded(modelInfo)
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
        val handle = engineHandle.get()
        if (handle != 0L) {
            NativeEngineHandle.nativeCancel(handle)
        }
    }

    override fun close() {
        cancelInference()
        val handle = engineHandle.getAndSet(0L)
        if (handle != 0L) {
            NativeEngineHandle.nativeDestroyEngine(handle)
        }
        _state.value = EngineState.Closed
        scope.cancel()
        Log.i(TAG, "LocalLlmEngine closed")
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
        val h = engineHandle.get()
        check(h != 0L) { "Engine handle is invalid or closed" }
        return h
    }
}
