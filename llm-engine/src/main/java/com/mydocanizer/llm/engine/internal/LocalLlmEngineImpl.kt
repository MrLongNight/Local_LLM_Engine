package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.util.Log
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BuildConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal class LocalLlmEngineImpl(
    private val context: Context
) : LocalLlmEngine {

    companion object {
        private const val TAG = "LocalLlmEngineImpl"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    // Native handle destruction is queued on the same single-lane dispatcher as every
    // load/prefill/token operation. This prevents close() from freeing llama_context
    // while nativeGenerateNextToken() is still using it.
    private val cleanupScope = CoroutineScope(SupervisorJob() + llamaDispatcher)
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

            val diagnosticsDirectory = File(context.filesDir, "diagnostics")
            if (!diagnosticsDirectory.exists() && !diagnosticsDirectory.mkdirs()) {
                throw IllegalStateException("Could not create native diagnostics directory")
            }
            val nativeDiagnosticsFile = File(
                diagnosticsDirectory,
                "engine-native-${android.os.Process.myPid()}.log"
            )
            val loaded = NativeEngineHandle.ensureLoaded(nativeDiagnosticsFile)
            if (!loaded) {
                throw IllegalStateException("Failed to load native library localllm_engine")
            }

            EngineDiagnostics.info(
                "engine",
                "NATIVE_LOGGER_READY",
                "nativeLogFile=${nativeDiagnosticsFile.name}; minimumLevel=INFO; promptAndResponseContentNotRecorded"
            )
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
            EngineDiagnostics.info(
                "engine",
                "NATIVE_SYSTEM_INFO",
                sysInfo.replace('\n', ' ').take(1200)
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
        // JNI inference is blocking. Signal the native cancellation flag before
        // initialization/model loading queues behind the single native dispatcher.
        if (_state.value is EngineState.Inferring) {
            EngineDiagnostics.info("engine", "MODEL_LOAD_PREEMPTS_INFERENCE", "Cancelling active inference before model load")
            cancelInference()
        }
        val initializationError = initialize().exceptionOrNull()
        if (initializationError != null) return Result.failure(initializationError)

        return withContext(llamaDispatcher) {
            runCatching {
                val handle = getValidHandle()
            _state.value = EngineState.LoadingModel(source)

            val file: File = ModelSourceResolver.resolveToFile(context, source)
            val directDescriptorFd = (source as? ModelSource.FilePath)
                ?.takeIf { it.descriptor != null && it.descriptorFd != null }
                ?.descriptorFd ?: -1
            Log.i(TAG, "Loading model file: ${file.absolutePath} (${file.length()} bytes)")
            EngineDiagnostics.info(
                "engine",
                "MODEL_LOAD_STARTED",
                "fileBytes=${file.length()}, directDescriptorFd=$directDescriptorFd, backendPreference=${config.backendPreference}, " +
                    "threads=${config.threads}, batchThreads=${config.batchThreads.takeIf { it > 0 } ?: config.threads}, " +
                    "batch=${config.batchSize}, microBatch=${config.microBatchSize.takeIf { it > 0 } ?: config.batchSize}, " +
                    "context=${config.contextSize}, gpuLayers=${config.gpuLayers}"
            )
            val backendPreflight = runCatching {
                NativeEngineHandle.nativeGetBackendDiagnostics()
            }.getOrElse { error ->
                "backendDiagnosticsQueryFailed=${error.javaClass.simpleName}:${error.message}"
            }
            EngineDiagnostics.info(
                "engine",
                "NATIVE_BACKEND_PREFLIGHT",
                backendPreflight.take(1800)
            )

            updateModelLoadProgress(0)
            val nativeLoadStartNano = System.nanoTime()
            var lastProgressBucket = -1
            val progressCallback = NativeModelLoadProgressCallback { progress ->
                val percent = (progress.coerceIn(0.0f, 1.0f) * 100.0f)
                    .roundToInt().coerceIn(0, 99)
                updateModelLoadProgress(percent)
                val bucket = percent / 5
                if (bucket > lastProgressBucket) {
                    lastProgressBucket = bucket
                    EngineDiagnostics.info(
                        "engine",
                        "MODEL_LOAD_PROGRESS",
                        "percent=$percent, backendPreference=${config.backendPreference}, " +
                            "gpuLayers=${config.gpuLayers}, fileBytes=${file.length()}"
                    )
                }
            }

            val ret = NativeEngineHandle.nativeLoadModel(
                handle = handle,
                modelPath = file.absolutePath,
                modelFd = directDescriptorFd,
                contextSize = config.contextSize,
                batchSize = config.batchSize,
                threads = config.threads,
                temperature = config.temperature,
                topP = config.topP,
                backendMode = config.backendPreference.modeId,
                gpuLayers = config.gpuLayers,
                batchThreads = config.batchThreads,
                microBatchSize = config.microBatchSize,
                progressCallback = progressCallback
            )

            when (ret) {
                0 -> { /* Success */ }
                103 -> throw IllegalStateException(
                    "Native model loader raised a C++ exception. See preceding native load diagnostics."
                )
                104 -> throw IllegalStateException(
                    "Native context creation raised a C++ exception."
                )
                105 -> throw IllegalStateException(
                    "Native chat-template or sampler setup raised a C++ exception."
                )
                106 -> throw IllegalStateException(
                    "Native layer could not duplicate or wrap the borrowed SAF descriptor. See native diagnostics."
                )
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
                    "threads=${config.threads}, batchThreads=${config.batchThreads.takeIf { it > 0 } ?: config.threads}, " +
                    "batch=${config.batchSize}, microBatch=${config.microBatchSize.takeIf { it > 0 } ?: config.batchSize}, " +
                    "context=${config.contextSize}, gpuLayers=${config.gpuLayers}"
            )
            val rawMetadata = NativeEngineHandle.nativeGetModelMetadata(handle)
            updateModelLoadProgress(87) // native runtime ready; metadata has been read
            // The backend description itself contains a semicolon (device; requested layers X/Y).
            // Preserve it as the final field instead of truncating ModelInfo.activeBackend.
            val parts = rawMetadata.split(";", limit = 5)
            val desc = parts.getOrNull(0) ?: ""
            val nParams = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val nCtxTrain = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull() ?: file.length()
            val activeBackend = parts.getOrNull(4) ?: NativeEngineHandle.nativeGetActiveBackend(handle)

            val isDirectDescriptor = source is ModelSource.FilePath &&
                source.path.startsWith("/proc/self/fd/")
            val shaStartNano = System.nanoTime()
            val importedSha256 = if (isDirectDescriptor) null else trustedImportedSha256(file)
            val sha256 = when {
                isDirectDescriptor -> {
                    EngineDiagnostics.info(
                        "engine",
                        "MODEL_SHA256_SKIPPED_DIRECT_SOURCE",
                        "reason=avoid_second_full_file_read,fileBytes=${file.length()}"
                    )
                    null
                }
                importedSha256 != null -> importedSha256
                else -> calculateSha256(file) { percent -> updateModelLoadProgress(percent) }
            }
            if (importedSha256 != null) {
                updateModelLoadProgress(99)
                EngineDiagnostics.info(
                    "engine",
                    "MODEL_SHA256_REUSED_IMPORT_DIGEST",
                    "trustedPrivateImportName=true, fileBytes=${file.length()}"
                )
            } else if (sha256 != null) {
                EngineDiagnostics.info(
                    "engine",
                    "MODEL_SHA256_COMPLETED",
                    "durationMs=${(System.nanoTime() - shaStartNano) / 1_000_000L}, fileBytes=${file.length()}"
                )
            }

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

    override suspend fun unloadModel(): Result<Unit> {
        if (_state.value is EngineState.Inferring) {
            EngineDiagnostics.info(
                "engine",
                "MODEL_UNLOAD_PREEMPTS_INFERENCE",
                "Cancelling active inference before unload is queued"
            )
            cancelInference()
        }
        return withContext(llamaDispatcher) {
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
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val inferenceRunId = UUID.randomUUID().toString()
        val handle = getValidHandle()

        // In Debug builds only, instrumentation can arm a non-cooperative block at the
        // exact JNI entry point used by normal chat. It runs before the model-presence check
        // so CI can verify hard process abort without bundling a large GGUF test model.
        if (BuildConfig.DEBUG && NativeEngineHandle.nativeIsBlockNextInferenceArmedForTest(handle)) {
            EngineDiagnostics.info(
                "inference",
                "DEBUG_NATIVE_INFERENCE_BLOCK_REQUESTED",
                "Injecting a deliberate non-cooperative block inside nativeProcessChat"
            )
            NativeEngineHandle.nativeProcessChat(
                handle = handle,
                inferenceRunId = "debug-$inferenceRunId",
                roles = emptyArray(),
                contents = emptyArray(),
                maxPredict = request.maxPredictTokens,
                temperature = request.temperature ?: Float.NaN,
                topP = request.topP ?: Float.NaN,
                enableThinking = request.enableThinking,
                progressCallback = NativeInferenceProgressCallback { _, _ -> }
            )
        }

        val modelInfo = _currentModel.value
            ?: throw IllegalStateException("No model loaded. Call loadModel() before chat.")

        // A Binder cancel can arrive after the service accepted the request but before
        // this cold Flow reaches its native lane. Do not clear that cancellation and start
        // work that the caller has already abandoned.
        if (isCancelled.get()) {
            trySend(InferenceEvent.Cancelled("", 0))
            _state.value = EngineState.ModelLoaded(modelInfo)
            isCancelled.set(false)
            return@callbackFlow
        }

        _state.value = EngineState.Inferring(request)
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
            "runId=$inferenceRunId,pid=${android.os.Process.myPid()},messages=${request.messages.size}, " +
                "maxPredictTokens=${request.maxPredictTokens},backend=${modelInfo.activeBackend}"
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

        // Clear the previous request's native cancellation flag only after checking the
        // Kotlin-side flag. Check it again after reset so a cancel racing with reset is
        // either handled here or observed inside llama.cpp's CPU abort callback.
        NativeEngineHandle.nativeResetCancellation(handle)
        if (isCancelled.get()) {
            trySend(InferenceEvent.Cancelled("", 0))
            _state.value = EngineState.ModelLoaded(modelInfo)
            isCancelled.set(false)
            return@callbackFlow
        }

        val processRes = NativeEngineHandle.nativeProcessChat(
            handle = handle,
            inferenceRunId = inferenceRunId,
            roles = roles,
            contents = contents,
            maxPredict = request.maxPredictTokens,
            temperature = request.temperature ?: Float.NaN,
            topP = request.topP ?: Float.NaN,
            enableThinking = request.enableThinking,
            progressCallback = progressCallback
        )

        if (processRes == 6 || isCancelled.get()) {
            val cancelledTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(0)
            EngineDiagnostics.info(
                "inference",
                "PREFILL_CANCELLED",
                "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                    "tokensGenerated=$cancelledTokens,nativeResult=$processRes"
            )
            trySend(InferenceEvent.Cancelled("", cancelledTokens))
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
            return@callbackFlow
        }

        if (processRes != 0) {
            val message = when (processRes) {
                1 -> "Native prompt evaluation has no valid model/context handle"
                2 -> "The native prompt message list is invalid"
                3 -> "The chat template produced no prompt tokens"
                4 -> "llama.cpp failed while decoding the prompt"
                5 -> "The model chat template could not be formatted"
                7 -> "Native request sampler initialization failed"
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
        val promptMetrics = NativeEngineHandle.nativeGetLastPromptMetrics(handle)
        EngineDiagnostics.info(
            "inference",
            "PROMPT_STAGE_METRICS",
            "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                "backend=${modelInfo.activeBackend},$promptMetrics"
        )
        EngineDiagnostics.info(
            "inference",
            "PREFILL_COMPLETED",
            "runId=$inferenceRunId,pid=${android.os.Process.myPid()},messages=${request.messages.size}, " +
                "prefillMs=$prefillDurationMs,backend=${modelInfo.activeBackend}"
        )
        val prefillCompletedNano = System.nanoTime()
        trySend(InferenceEvent.Started(request.messages.size))

        val outputBuilder = StringBuilder()
        var tokenCount = 0
        var nativeTokenSteps = 0
        var firstVisibleTokenRecorded = false

        try {
            while (!isCancelled.get()) {
                val tokenStepStartNano = System.nanoTime()
                val tokenPiece = NativeEngineHandle.nativeGenerateNextToken(handle)
                val tokenStepDurationUs = (System.nanoTime() - tokenStepStartNano) / 1_000L
                if (tokenPiece == null) {
                    break
                }
                nativeTokenSteps++
                if (nativeTokenSteps <= 4 || nativeTokenSteps % 8 == 0) {
                    EngineDiagnostics.info(
                        "inference",
                        "TOKEN_STEP_METRIC",
                        "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                            "nativeStep=$nativeTokenSteps,jniWallUs=$tokenStepDurationUs, " +
                            "visiblePieces=$tokenCount,backend=${modelInfo.activeBackend}"
                    )
                }
                if (tokenPiece.isNotEmpty()) {
                    if (!firstVisibleTokenRecorded) {
                        firstVisibleTokenRecorded = true
                        val latencyAfterPrefillMs =
                            (System.nanoTime() - prefillCompletedNano) / 1_000_000L
                        EngineDiagnostics.info(
                            "inference",
                            "FIRST_TOKEN_RECEIVED",
                            "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                                "latencyAfterPrefillMs=$latencyAfterPrefillMs,backend=${modelInfo.activeBackend}"
                        )
                    }
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
                "runId=$inferenceRunId,pid=${android.os.Process.myPid()},tokens=$actualTokens, " +
                    "generationMs=$durationMs,tokensPerSecond=$tokensPerSec,finishReason=$finishReason, " +
                    "backend=${modelInfo.activeBackend}"
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
        isCancelled.set(true)
        val handle = engineHandle.getAndSet(0L)
        // nativeCancel only flips the EngineInstance atomic flag; it deliberately does
        // not acquire the model mutex, so it can interrupt an in-flight decode.
        if (handle != 0L) {
            runCatching { NativeEngineHandle.nativeCancel(handle) }
                .onFailure { EngineDiagnostics.error("native", "NATIVE_CANCEL_DURING_CLOSE_FAILED", it) }
        }

        // AutoCloseable cannot suspend. Queue destruction behind any in-flight JNI work
        // on llamaDispatcher rather than freeing a context from the caller/UI/service thread.
        cleanupScope.launch {
            try {
                if (handle != 0L) {
                    NativeEngineHandle.nativeDestroyEngine(handle)
                }
            } catch (error: Throwable) {
                EngineDiagnostics.error("native", "NATIVE_ENGINE_DESTROY_FAILED", error)
            } finally {
                // Backend init is process-global, so pair it with shutdown after the
                // handle has been destroyed on the same serialized dispatcher.
                runCatching { NativeEngineHandle.shutdownBackend() }
                    .onFailure { EngineDiagnostics.error("native", "BACKEND_SHUTDOWN_FAILED", it) }
                cleanupScope.cancel()
            }
        }
        _state.value = EngineState.Closed
        Log.i(TAG, "LocalLlmEngine close queued on llamaDispatcher")
    }

    private fun updateModelLoadProgress(percent: Int) {
        val safePercent = percent.coerceIn(0, 100)
        if (_modelLoadProgress.value != safePercent) {
            _modelLoadProgress.value = safePercent
            _modelLoadProgressEvents.tryEmit(safePercent)
        }
    }

    /**
     * Imports created by ModelSourceResolver are stored privately as
     * <sha256>-<display-name>.gguf. Trust that digest only inside our private models
     * directory; arbitrary external FilePath names are never treated as hashes.
     */
    private fun trustedImportedSha256(file: File): String? = runCatching {
        val privateModelsDir = File(context.filesDir, "models").canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile.parentFile != privateModelsDir) {
            return@runCatching null
        }
        val separator = canonicalFile.name.indexOf('-')
        if (separator != 64) return@runCatching null
        canonicalFile.name.substring(0, separator)
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    private fun calculateSha256(file: File, onProgress: (Int) -> Unit): String? {
        val result = runCatching {
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
        }
        result.onFailure { EngineDiagnostics.error("engine", "MODEL_SHA256_FAILED", it) }
        return result.getOrNull()
    }

    internal fun armNativeInferenceBlockForTest() {
        check(BuildConfig.DEBUG) { "Native inference test hooks are unavailable in Release builds" }
        NativeEngineHandle.nativeArmBlockNextInferenceForTest(getValidHandle())
    }

    internal fun isNativeInferenceBlockEnteredForTest(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val handle = engineHandle.get()
        return handle != 0L && NativeEngineHandle.nativeIsNativeInferenceBlockEnteredForTest(handle)
    }

    private fun getValidHandle(): Long {
        val h = engineHandle.get()
        check(h != 0L) { "Engine handle is invalid or closed" }
        return h
    }
}
