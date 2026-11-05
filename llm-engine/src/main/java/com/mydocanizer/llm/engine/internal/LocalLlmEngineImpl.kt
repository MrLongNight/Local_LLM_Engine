package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BackendPreference
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flowOn
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
    private val initializationMutex = Mutex()

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

    private val engineHandle = AtomicLong(0L)
    private val isCancelled = AtomicBoolean(false)

    override suspend fun initialize(): Result<Unit> = initializationMutex.withLock {
        withContext(llamaDispatcher) {
            runCatching {
                if (engineHandle.get() != 0L) {
                    if (_state.value !is EngineState.ModelLoaded) _state.value = EngineState.Ready
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
                EngineDiagnostics.error("engine", "INITIALIZATION_FAILED", err)
                Log.e(TAG, "Initialization failed", err)
                _state.value = EngineState.Error("Failed to initialize engine: ${err.message}", err)
            }
        }
    }

    override suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig
    ): Result<ModelInfo> {
        val initializationError = initialize().exceptionOrNull()
        if (initializationError != null) return Result.failure(initializationError)

        return withContext(llamaDispatcher) {
            runCatching {
                val handle = getValidHandle()
            _state.value = EngineState.LoadingModel(source)

            val file: File = ModelSourceResolver.resolveToFile(context, source)
            Log.i(TAG, "Loading model file: ${file.absolutePath} (${file.length()} bytes)")
            EngineDiagnostics.info(
                "engine",
                "MODEL_LOAD_STARTED",
                "fileBytes=${file.length()}, backendPreference=${config.backendPreference}, " +
                    "threads=${config.threads}, batch=${config.batchSize}, context=${config.contextSize}, gpuLayers=${config.gpuLayers}"
            )
            updateModelLoadProgress(0)
            val nativeLoadStartNano = System.nanoTime()
            val progressCallback = NativeModelLoadProgressCallback { progress ->
                updateModelLoadProgress(
                    (progress.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt().coerceIn(0, 99)
                )
            }

            val ret = NativeEngineHandle.nativeLoadModel(
                handle = handle,
                modelPath = file.absolutePath,
                contextSize = config.contextSize,
                batchSize = config.batchSize,
                threads = config.threads,
                temperature = config.temperature,
                topP = config.topP,
                backendMode = config.backendPreference.modeId,
                gpuLayers = config.gpuLayers,
                progressCallback = progressCallback
            )

            when (ret) {
                0 -> { /* Success */ }
                101 -> throw IllegalStateException(
                    "Vulkan backend was explicitly requested, but no Vulkan GPU device is available or initialization failed."
                )
                else -> throw IllegalStateException("Native model load failed with error code: $ret")
            }

            val nativeLoadDurationMs = (System.nanoTime() - nativeLoadStartNano) / 1_000_000L
            EngineDiagnostics.info(
                "engine",
                "MODEL_NATIVE_RUNTIME_READY",
                "durationMs=$nativeLoadDurationMs, backend=${NativeEngineHandle.nativeGetActiveBackend(handle)}, " +
                    "threads=${config.threads}, batch=${config.batchSize}, context=${config.contextSize}, gpuLayers=${config.gpuLayers}"
            )
            val rawMetadata = NativeEngineHandle.nativeGetModelMetadata(handle)
            updateModelLoadProgress(87) // native runtime ready; metadata has been read
            val parts = rawMetadata.split(";")
            val desc = parts.getOrNull(0) ?: ""
            val nParams = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val nCtxTrain = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull() ?: file.length()
            val activeBackend = parts.getOrNull(4) ?: NativeEngineHandle.nativeGetActiveBackend(handle)

            val shaStartNano = System.nanoTime()
            val sha256 = calculateSha256(file) { percent ->
                updateModelLoadProgress(percent)
            }
            EngineDiagnostics.info(
                "engine",
                "MODEL_SHA256_COMPLETED",
                "durationMs=${(System.nanoTime() - shaStartNano) / 1_000_000L}, fileBytes=${file.length()}"
            )

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

            updateModelLoadProgress(100)
            _currentModel.value = info
            _state.value = EngineState.ModelLoaded(info)
            Log.i(TAG, "Model loaded successfully: $info")
            info
            }.onFailure { err ->
                _modelLoadProgress.value = null
                EngineDiagnostics.error("engine", "MODEL_LOAD_FAILED", err)
                Log.e(TAG, "Model load failed", err)
                _state.value = EngineState.Error("Failed to load model: ${err.message}", err)
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> = withContext(llamaDispatcher) {
        runCatching {
            val handle = engineHandle.get()
            if (handle != 0L) {
                NativeEngineHandle.nativeUnloadModel(handle)
            }
            _currentModel.value = null
            _modelLoadProgress.value = null
            _state.value = EngineState.Ready
            Log.i(TAG, "Model unloaded successfully")
            Unit
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val modelInfo = _currentModel.value
            ?: throw IllegalStateException("No model loaded. Call loadModel() before chat.")

        val handle = getValidHandle()
        _state.value = EngineState.Inferring(request)
        isCancelled.set(false)

        val requestStartNano = System.nanoTime()
        val roles = request.messages.map { it.role.roleName }.toTypedArray()
        val contents = request.messages.map { it.content }.toTypedArray()

        trySend(
            InferenceEvent.Progress(
                tokensGenerated = 0,
                elapsedMs = 0L,
                phase = InferenceEvent.ProgressPhase.PROMPT_PREPARATION
            )
        )
        EngineDiagnostics.info(
            "inference",
            "PROMPT_EVALUATION_STARTED",
            "messages=${request.messages.size}, maxPredictTokens=${request.maxPredictTokens}, backend=${modelInfo.activeBackend}"
        )

        val progressCallback = NativeInferenceProgressCallback { processed, total ->
            val elapsedMs = (System.nanoTime() - requestStartNano) / 1_000_000L
            val safeProcessed = processed.coerceAtLeast(0)
            val safeTotal = total.coerceAtLeast(0)
            val speed = if (elapsedMs > 0L) safeProcessed * 1000.0 / elapsedMs else 0.0
            val etaMs = if (speed > 0.0 && safeTotal > safeProcessed) {
                (((safeTotal - safeProcessed) * 1000.0) / speed).toLong()
            } else null
            trySend(
                InferenceEvent.Progress(
                    tokensGenerated = 0,
                    elapsedMs = elapsedMs,
                    phase = InferenceEvent.ProgressPhase.PROMPT_EVALUATION,
                    promptTokensProcessed = safeProcessed,
                    promptTokensTotal = safeTotal,
                    tokensPerSecond = speed,
                    estimatedRemainingMs = etaMs
                )
            )
        }

        val processRes = NativeEngineHandle.nativeProcessChat(
            handle = handle,
            roles = roles,
            contents = contents,
            maxPredict = request.maxPredictTokens,
            progressCallback = progressCallback
        )

        if (processRes != 0) {
            val message = when (processRes) {
                1 -> "Native prompt evaluation has no valid model/context handle"
                2 -> "The native prompt message list is invalid"
                3 -> "The chat template produced no prompt tokens"
                4 -> "llama.cpp failed while decoding the prompt"
                5 -> "The model chat template could not be formatted"
                else -> "Native prompt evaluation failed with code $processRes"
            }
            val err = IllegalStateException(message)
            EngineDiagnostics.error("native", "PROMPT_EVALUATION_FAILED", err)
            trySend(InferenceEvent.Error(err))
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
            return@callbackFlow
        }

        val prefillDurationMs = (System.nanoTime() - requestStartNano) / 1_000_000L
        EngineDiagnostics.info(
            "inference",
            "PREFILL_COMPLETED",
            "messages=${request.messages.size}, prefillMs=$prefillDurationMs, backend=${modelInfo.activeBackend}"
        )
        trySend(InferenceEvent.Started(request.messages.size))

        val outputBuilder = StringBuilder()
        var tokenCount = 0
        var nativeTokenSteps = 0

        try {
            while (!isCancelled.get()) {
                val tokenStepStartNano = System.nanoTime()
                val tokenPiece = NativeEngineHandle.nativeGenerateNextToken(handle)
                val tokenStepDurationMs = (System.nanoTime() - tokenStepStartNano) / 1_000_000L
                if (tokenPiece == null) {
                    break
                }
                nativeTokenSteps++
                if (nativeTokenSteps <= 4 || nativeTokenSteps % 8 == 0) {
                    EngineDiagnostics.info(
                        "inference",
                        "TOKEN_STEP_METRIC",
                        "nativeStep=$nativeTokenSteps, durationMs=$tokenStepDurationMs, " +
                            "visiblePieces=$tokenCount, backend=${modelInfo.activeBackend}"
                    )
                }
                if (tokenPiece.isNotEmpty()) {
                    outputBuilder.append(tokenPiece)
                    trySend(InferenceEvent.Token(tokenPiece, tokenCount))
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
                else -> FinishReason.ERROR
            }

            val durationUs = NativeEngineHandle.nativeGetInferenceDurationUs(handle)
            val durationMs = (durationUs / 1000L).coerceAtLeast(1L)
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            val tokensPerSec = (actualTokens * 1000.0) / durationMs
            val fullText = outputBuilder.toString()
            EngineDiagnostics.info(
                "inference",
                "GENERATION_COMPLETED",
                "tokens=$actualTokens, generationMs=$durationMs, tokensPerSecond=$tokensPerSec, finishReason=$finishReason, backend=${modelInfo.activeBackend}"
            )

            when (finishReason) {
                FinishReason.CANCELLED -> trySend(InferenceEvent.Cancelled(fullText, actualTokens))
                FinishReason.ERROR -> {
                    val error = IllegalStateException(
                        "Native token generation failed (llama.cpp finish reason=$rawReason)"
                    )
                    EngineDiagnostics.error("native", "TOKEN_GENERATION_FAILED", error)
                    trySend(InferenceEvent.Error(error, fullText))
                }
                else -> {
                    val result = InferenceResult(
                        text = fullText,
                        tokensGenerated = actualTokens,
                        durationMs = durationMs,
                        tokensPerSecond = tokensPerSec,
                        finishReason = finishReason
                    )
                    trySend(InferenceEvent.Completed(result))
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "streamChat coroutine cancelled, notifying native engine")
            NativeEngineHandle.nativeCancel(handle)
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            trySend(InferenceEvent.Cancelled(outputBuilder.toString(), actualTokens))
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error during generation loop", t)
            EngineDiagnostics.error("inference", "GENERATION_LOOP_FAILED", t)
            trySend(InferenceEvent.Error(t, outputBuilder.toString()))
        } finally {
            isCancelled.set(false)
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
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
        Log.i(TAG, "LocalLlmEngine closed")
    }

    private fun updateModelLoadProgress(percent: Int) {
        val safePercent = percent.coerceIn(0, 100)
        if (_modelLoadProgress.value != safePercent) {
            _modelLoadProgress.value = safePercent
            _modelLoadProgressEvents.tryEmit(safePercent)
        }
    }

    private fun calculateSha256(file: File, onProgress: (Int) -> Unit): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val totalBytes = file.length().coerceAtLeast(1L)
        var processedBytes = 0L
        var lastReportedPercent = 86
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                processedBytes += bytesRead
                val percent = (87.0 + (processedBytes.toDouble() / totalBytes.toDouble()) * 12.0)
                    .roundToInt()
                    .coerceIn(87, 99)
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    onProgress(percent)
                }
            }
        }
        onProgress(99)
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("N/A")

    private fun getValidHandle(): Long {
        val h = engineHandle.get()
        check(h != 0L) { "Engine handle is invalid or closed" }
        return h
    }
}
