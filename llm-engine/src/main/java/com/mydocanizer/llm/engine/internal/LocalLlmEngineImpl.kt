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
import com.mydocanizer.llm.engine.NativeModelMetadataParser
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal fun isCooperativePrefillCancellation(
    nativeResult: Int,
    cancellationRequested: Boolean
): Boolean = nativeResult == 6 || (nativeResult == 0 && cancellationRequested)

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
    @Volatile
    private var loadedConfig: EngineConfig? = null

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
            val profilerEnabled = BuildConfig.VULKAN_PERF_PROFILER_ENABLED
            val loaded = NativeEngineHandle.ensureLoaded(
                diagnosticsLogFile = nativeDiagnosticsFile,
                enableVulkanPerfProfiler = profilerEnabled
            )
            if (!loaded) {
                throw IllegalStateException("Failed to load native library localllm_engine")
            }

            EngineDiagnostics.info(
                "engine",
                "NATIVE_LOGGER_READY",
                "nativeLogFile=${nativeDiagnosticsFile.name}; minimumLevel=INFO; vulkanPerfProfilerEnabled=$profilerEnabled; ordinaryPromptContentNotRecorded; generatedResponseTextRecordedWithRunId"
            )
            val handle = NativeEngineHandle.nativeCreateEngine()
            if (handle == 0L) {
                throw IllegalStateException("Failed to allocate native EngineInstance")
            }
            engineHandle.set(handle)

            val sysInfo = runCatching { NativeEngineHandle.nativeGetSystemInfo() }.getOrDefault("")
            val recThreads = CpuThreadPolicy.recommendedThreads()
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
                if (err is CancellationException) {
                    _state.value = EngineState.Uninitialized
                    throw err
                }
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
        val previousModel = _currentModel.value

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
                    "threads=${config.threads}, batchThreads=${config.effectiveBatchThreads}, " +
                    "batch=${config.batchSize}, microBatchRequested=${config.microBatchSize}, " +
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
                topK = config.topK,
                minP = config.minP,
                backendMode = config.backendPreference.modeId,
                gpuLayers = config.gpuLayers,
                batchThreads = config.batchThreads,
                microBatchSize = config.microBatchSize,
                flashAttentionMode = config.flashAttentionMode.nativeId,
                vulkanDmmvWorkgroup = config.vulkanMatvecWorkgroup.nativeId,
                gdnPolicy = config.gdnPolicy.nativeId,
                kvCacheTypeK = config.kvCacheTypeK.nativeId,
                kvCacheTypeV = config.kvCacheTypeV.nativeId,
                progressCallback = progressCallback
            )

            logNativeModelResourceState(handle, stage = "AFTER_LOAD_ATTEMPT", loadResult = ret)

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
                107 -> throw IllegalStateException(
                    "Gemma 4 assistant is a draft head, not a standalone chat model. It requires a paired target-model context and shared KV state."
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
                    "threads=${config.threads}, batchThreads=${config.effectiveBatchThreads}, " +
                    "batch=${config.batchSize}, microBatchRequested=${config.microBatchSize}, " +
                    "context=${config.contextSize}, gpuLayers=${config.gpuLayers}"
            )
            val rawMetadata = NativeEngineHandle.nativeGetModelMetadata(handle)
            updateModelLoadProgress(87) // native runtime ready; metadata has been read
            // Architecture is read from GGUF metadata. The parser accepts the new
            // ARCH= field and still preserves legacy semicolon-containing backend strings.
            val parsedMetadata = NativeModelMetadataParser.parse(
                rawMetadata = rawMetadata,
                fallbackBackend = NativeEngineHandle.nativeGetActiveBackend(handle),
                fallbackSizeBytes = file.length()
            )
            val desc = parsedMetadata.description
            val nParams = parsedMetadata.parameterCount
            val nCtxTrain = parsedMetadata.contextLengthTrain
            val sizeBytes = parsedMetadata.sizeBytes
            val architecture = parsedMetadata.architecture
            val activeBackend = parsedMetadata.activeBackend

            val isDirectDescriptor = source is ModelSource.FilePath &&
                source.path.startsWith("/proc/self/fd/")
            val shaStartNano = System.nanoTime()
            // A direct descriptor may still target our private content-addressed import.
            // Reuse that trusted import digest before deciding whether a second read is safe.
            val importedSha256 = trustedImportedSha256(file)
            val sha256 = when {
                importedSha256 != null -> importedSha256
                isDirectDescriptor -> {
                    EngineDiagnostics.info(
                        "engine",
                        "MODEL_SHA256_SKIPPED_DIRECT_SOURCE",
                        "reason=avoid_second_full_file_read,fileBytes=" + file.length()
                    )
                    null
                }
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
                    "sha256=$sha256,durationMs=${(System.nanoTime() - shaStartNano) / 1_000_000L},fileBytes=${file.length()}"
                )
            }

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

            EngineDiagnostics.info(
                "engine",
                "MODEL_IDENTITY",
                "model=${info.name},sizeBytes=${info.sizeBytes},architecture=${info.architecture}," +
                    "parameterCount=${info.parameterCount},trainedContext=${info.contextLengthTrain}," +
                    "description=${info.description}," +
                    "sha256=${info.sha256 ?: "unavailable"}," +
                    "sha256Status=${if (info.sha256 != null) "VERIFIED_OR_TRUSTED_IMPORT" else "SKIPPED_DIRECT_DESCRIPTOR"}"
            )
            loadedConfig = config
            updateModelLoadProgress(100)
            _currentModel.value = info
            _state.value = EngineState.ModelLoaded(info)
            Log.i(TAG, "Model loaded successfully: $info")
            info
            }.onFailure { err ->
                _modelLoadProgress.value = null
                if (err is CancellationException) {
                    _state.value = if (previousModel != null) {
                        EngineState.ModelLoaded(previousModel)
                    } else {
                        EngineState.Ready
                    }
                    throw err
                }
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
                    logNativeModelResourceState(handle, stage = "AFTER_UNLOAD")
                }
                _currentModel.value = null
                loadedConfig = null
                _modelLoadProgress.value = null
                _state.value = EngineState.Ready
                Log.i(TAG, "Model unloaded successfully")
                Unit
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun streamChat(request: InferenceRequest): Flow<InferenceEvent> = channelFlow {
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
                grammar = request.grammar.orEmpty(),
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
            return@channelFlow
        }

        // Token events must never be dropped when the UI/Binder collector is slower
        // than native decode. Prompt progress is independently conflated so only the
        // latest pending progress sample is forwarded while native prefill is busy.
        val progressEvents = Channel<InferenceEvent.Progress>(Channel.CONFLATED)
        val nativeWorkActive = AtomicBoolean(false)
        // This producer performs native work itself and closes its channel in finally.
        // It is intentionally channelFlow (not callbackFlow): callbackFlow is reserved
        // for externally registered callbacks whose lifetime is paired with awaitClose.
        // invokeOnClose remains the cancellation hook so an external collector aborts
        // a native prefill/decode that is currently executing on llamaDispatcher.
        channel.invokeOnClose {
            progressEvents.close()
            if (nativeWorkActive.get()) {
                isCancelled.set(true)
                NativeEngineHandle.nativeCancel(handle)
            }
        }
        launch(Dispatchers.Default) {
            for (progress in progressEvents) {
                if (trySend(progress).isFailure) {
                    isCancelled.set(true)
                    NativeEngineHandle.nativeCancel(handle)
                    break
                }
            }
        }

        _state.value = EngineState.Inferring(request)
        val requestStartNano = System.nanoTime()
        var prefillDurationUs = -1L
        var ttftUs = -1L
        var phaseMetricsLogged = false
        fun logInferencePhaseMetrics(
            status: String,
            finishReason: String,
            generatedTokens: Int,
            decodeDurationUs: Long
        ) {
            if (phaseMetricsLogged) return
            phaseMetricsLogged = true
            EngineDiagnostics.info(
                "inference",
                "INFERENCE_PHASE_METRICS",
                "runId=$inferenceRunId,pid=${android.os.Process.myPid()}," +
                    "backend=${modelInfo.activeBackend},prefillUs=$prefillDurationUs," +
                    "ttftUs=$ttftUs,decodeUs=$decodeDurationUs," +
                    "finishReason=$finishReason,status=$status,generatedTokens=$generatedTokens"
            )
        }
        val nativeMessages = NativePromptMessageMapper.map(request.messages)
        val roles = nativeMessages.roles
        val contents = nativeMessages.contents

        progressEvents.trySend(
            InferenceEvent.Progress(
                tokensGenerated = 0,
                elapsedMs = 0L,
                phase = InferenceEvent.ProgressPhase.PROMPT_PREPARATION
            )
        )
        val activeConfig = loadedConfig
        val effectiveTimeout = request.timeoutConfig ?: activeConfig?.timeoutConfig
        val effectiveTimeoutSource = when {
            request.timeoutConfig != null -> "request_override"
            activeConfig?.timeoutConfig != null -> "loaded_model_config"
            else -> "unavailable"
        }
        EngineDiagnostics.info(
            "inference",
            "INFERENCE_RUN_CONTEXT",
            "runId=$inferenceRunId,pid=${android.os.Process.myPid()}," +
                "model=${modelInfo.name},modelBytes=${modelInfo.sizeBytes}," +
                "modelSha256=${modelInfo.sha256 ?: "unavailable"},architecture=${modelInfo.architecture}," +
                "activeBackend=${modelInfo.activeBackend}," +
                "backendPreference=${activeConfig?.backendPreference ?: "unknown"}," +
                "contextSize=${activeConfig?.contextSize ?: -1},batchSize=${activeConfig?.batchSize ?: -1}," +
                "microBatchRequested=${activeConfig?.microBatchSize ?: -1}," +
                "microBatchEffective=${activeConfig?.effectiveMicroBatchSize ?: -1}," +
                "threads=${activeConfig?.threads ?: -1},batchThreads=${activeConfig?.effectiveBatchThreads ?: -1}," +
                "gpuLayersRequested=${activeConfig?.gpuLayers ?: -1}," +
                "flashAttention=${activeConfig?.flashAttentionMode ?: "unknown"}," +
                "vulkanMatvecWorkgroup=${activeConfig?.vulkanMatvecWorkgroup ?: "unknown"}," +
                "gdnPolicy=${activeConfig?.gdnPolicy ?: "unknown"}," +
                "temperatureDefault=${activeConfig?.temperature ?: "unknown"}," +
                "temperatureOverride=${request.temperature ?: "default"}," +
                "topPDefault=${activeConfig?.topP ?: "unknown"},topPOverride=${request.topP ?: "default"}," +
                "topK=${activeConfig?.topK ?: -1},minP=${activeConfig?.minP ?: "unknown"}," +
                "maxPredictTokens=${request.maxPredictTokens},enableThinking=${request.enableThinking}," +
                "grammarEnabled=${!request.grammar.isNullOrEmpty()}," +
                "messageCount=${request.messages.size},inputChars=${request.messages.sumOf { it.content.length }}," +
                "timeoutSource=$effectiveTimeoutSource," +
                "timeoutServiceBindMs=${effectiveTimeout?.serviceBindMs ?: "unknown"}," +
                "timeoutModelLoadMs=${effectiveTimeout?.modelLoadMs ?: "unknown"}," +
                "timeoutPrefillMs=${effectiveTimeout?.prefillMs ?: "unknown"}," +
                "timeoutFirstTokenMs=${effectiveTimeout?.firstTokenMs ?: "unknown"}," +
                "timeoutInterTokenMs=${effectiveTimeout?.interTokenMs ?: "unknown"}," +
                "timeoutTotalInferenceMs=${effectiveTimeout?.totalInferenceMs ?: "unknown"}," +
                "timeoutAbortGracePeriodMs=${effectiveTimeout?.abortGracePeriodMs ?: "unknown"}," +
                "timeoutReconnectMs=${effectiveTimeout?.reconnectMs ?: "unknown"}," +
                "timeout=${effectiveTimeout ?: "unknown"}"
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
            progressEvents.trySend(
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
            return@channelFlow
        }

        // Publish the active flag before the final cancellation check. If the
        // collector closes just before this point, invokeOnClose has already closed
        // the channel; ensureActive then aborts. If it closes after this point,
        // invokeOnClose sees nativeWorkActive=true and signals nativeCancel().
        nativeWorkActive.set(true)
        val processRes = try {
            currentCoroutineContext().ensureActive()
            if (isCancelled.get()) {
                6 // dedicated cancellation result; native inference never starts
            } else {
                NativeEngineHandle.nativeProcessChat(
                    handle = handle,
                    inferenceRunId = inferenceRunId,
                    roles = roles,
                    contents = contents,
                    maxPredict = request.maxPredictTokens,
                    temperature = request.temperature ?: Float.NaN,
                    topP = request.topP ?: Float.NaN,
                    enableThinking = request.enableThinking,
                    grammar = request.grammar.orEmpty(),
                    progressCallback = progressCallback
                )
            }
        } catch (cancelled: CancellationException) {
            nativeWorkActive.set(false)
            prefillDurationUs = (System.nanoTime() - requestStartNano) / 1_000L
            logInferencePhaseMetrics("PREFILL_CANCELLED", "CANCELLED", 0, -1L)
            val cancelFlagConsumed = isCancelled.getAndSet(false)
            EngineDiagnostics.info(
                "inference", "INFERENCE_TERMINAL_FAILURE",
                "runId=$inferenceRunId,phase=NATIVE_PROMPT,finishReason=CANCELLED," +
                    "cancelFlagConsumed=$cancelFlagConsumed"
            )
            EngineDiagnostics.recordInferenceOutput(inferenceRunId, "", "CANCELLED_BEFORE_DECODE", 0)
            throw cancelled
        } catch (error: Throwable) {
            nativeWorkActive.set(false)
            prefillDurationUs = (System.nanoTime() - requestStartNano) / 1_000L
            logInferencePhaseMetrics("PREFILL_EXCEPTION", "ERROR", 0, -1L)
            val cancelFlagConsumed = isCancelled.getAndSet(false)
            EngineDiagnostics.info(
                "inference", "INFERENCE_TERMINAL_FAILURE",
                "runId=$inferenceRunId,phase=NATIVE_PROMPT,errorType=${error.javaClass.simpleName}," +
                    "cancelFlagConsumed=$cancelFlagConsumed"
            )
            EngineDiagnostics.recordInferenceOutput(inferenceRunId, "", "NATIVE_PROMPT_EXCEPTION", 0)
            EngineDiagnostics.error("inference", "NATIVE_PROMPT_CALL_FAILED", error)
            throw error
        }

        // Result 6 is llama.cpp's dedicated cooperative prefill-abort result. A cancel
        // can also race just after a successful native return (result 0), but must never
        // mask a genuine native failure such as PROMPT_TOO_LONG (result 8).
        if (isCooperativePrefillCancellation(processRes, isCancelled.get())) {
            nativeWorkActive.set(false)
            prefillDurationUs = (System.nanoTime() - requestStartNano) / 1_000L
            // This early prefill-cancel return bypasses the generation loop's finally block.
            // Consume the request-local Kotlin latch now so the next request is not cancelled
            // once before it reaches nativeResetCancellation().
            isCancelled.set(false)
            val cancelledTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(0)
            logInferencePhaseMetrics("PREFILL_CANCELLED", "CANCELLED", cancelledTokens, -1L)
            EngineDiagnostics.info(
                "inference",
                "PREFILL_CANCELLED",
                "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                    "tokensGenerated=$cancelledTokens,nativeResult=$processRes"
            )
            trySend(InferenceEvent.Cancelled("", cancelledTokens))
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
            return@channelFlow
        }

        if (processRes != 0) {
            nativeWorkActive.set(false)
            prefillDurationUs = (System.nanoTime() - requestStartNano) / 1_000L
            logInferencePhaseMetrics("PREFILL_FAILED", "ERROR", 0, -1L)
            // A cancel can race with a genuine native prompt failure. The failure remains
            // an Error; consume the Kotlin-side request latch so the next request is clean.
            val cancelFlagConsumed = isCancelled.getAndSet(false)
            val message = when (processRes) {
                1 -> "Native prompt evaluation has no valid model/context handle"
                2 -> "The native prompt message list is invalid"
                3 -> "The chat template produced no prompt tokens"
                4 -> "llama.cpp failed while decoding the prompt"
                5 -> "The model chat template could not be formatted"
                7 -> "Native request sampler initialization failed"
                9 -> "GBNF grammar is invalid or could not be initialized; verify its syntax and the root rule."
                8 -> "Prompt zu lang: Ältere Chat-Nachrichten wurden bereits entfernt, aber System-Prompt und letzte Nutzernachricht passen nicht gemeinsam in den Modellkontext. Verkleinere die letzte Nachricht oder erhöhe den Kontext."
                else -> "Native prompt evaluation failed with code $processRes"
            }
            val err = IllegalStateException(message)
            EngineDiagnostics.info(
                "inference", "INFERENCE_TERMINAL_FAILURE",
                "runId=$inferenceRunId,phase=PROMPT,backend=${modelInfo.activeBackend}," +
                    "nativeResult=$processRes,cancelFlagConsumed=$cancelFlagConsumed"
            )
            EngineDiagnostics.recordInferenceOutput(inferenceRunId, "", "PROMPT_ERROR_$processRes", 0)
            EngineDiagnostics.error("native", "PROMPT_EVALUATION_FAILED", err)
            trySend(InferenceEvent.Error(err))
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
            return@channelFlow
        }

        prefillDurationUs = (System.nanoTime() - requestStartNano) / 1_000L
        val prefillDurationMs = prefillDurationUs / 1_000L
        val promptMetrics = NativeEngineHandle.nativeGetLastPromptMetrics(handle)
        val graphSplits = promptMetrics.split(';')
            .firstOrNull { it.startsWith("graphSplits=") }
            ?.substringAfter('=')
            ?.toIntOrNull() ?: -1
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
                "prefillMs=$prefillDurationMs,prefillUs=$prefillDurationUs,backend=${modelInfo.activeBackend}"
        )
        val prefillCompletedNano = System.nanoTime()
        trySend(InferenceEvent.Started(request.messages.size))

        val outputBuilder = StringBuilder()
        var tokenCount = 0
        var nativeTokenSteps = 0
        var firstVisibleTokenRecorded = false
        var nativeOutputFinalizationAttempted = false

        // Stop native sampling before asking JNI for one final step. With the
        // cancellation flag set, nativeGenerateNextToken only drains a retained
        // incomplete UTF-8 suffix and never samples another model token.
        fun finalizePendingNativeOutput() {
            if (nativeOutputFinalizationAttempted) return
            nativeOutputFinalizationAttempted = true
            runCatching {
                NativeEngineHandle.nativeCancel(handle)
                NativeEngineHandle.nativeGenerateNextToken(handle)
            }.onSuccess { finalPiece ->
                if (!finalPiece.isNullOrEmpty()) {
                    outputBuilder.append(finalPiece)
                    trySend(InferenceEvent.Token(finalPiece, tokenCount))
                    tokenCount++
                }
            }.onFailure { error ->
                EngineDiagnostics.error("inference", "NATIVE_UTF8_FINALIZATION_FAILED", error)
            }
        }

        try {
            while (!isCancelled.get()) {
                currentCoroutineContext().ensureActive()
                val tokenStepStartNano = System.nanoTime()
                val tokenPiece = NativeEngineHandle.nativeGenerateNextToken(handle)
                val tokenStepDurationUs = (System.nanoTime() - tokenStepStartNano) / 1_000L
                // Read the trace before checking for null: native returns null after
                // terminal EOG/STOP, but that sampling step is the most important
                // evidence when diagnosing an empty first response.
                if (BuildConfig.TEXT_DIAGNOSTICS_ENABLED &&
                    (nativeTokenSteps < 8 || nativeTokenSteps % 16 == 0)
                ) {
                    val tokenTrace = NativeEngineHandle.nativeGetLastTokenSelectionTrace(handle)
                    if (tokenTrace.isNotBlank()) {
                        EngineDiagnostics.recordTokenSelectionTrace(
                            nativeRunId = inferenceRunId,
                            backend = modelInfo.activeBackend,
                            numericTrace = tokenTrace
                        )
                    }
                }
                if (tokenPiece == null) {
                    break
                }
                nativeTokenSteps++
                if (ttftUs < 0L) {
                    ttftUs = (System.nanoTime() - requestStartNano) / 1_000L
                    val afterPrefillUs = (System.nanoTime() - prefillCompletedNano) / 1_000L
                    EngineDiagnostics.info(
                        "inference",
                        "TTFT_METRIC",
                        "runId=$inferenceRunId,pid=${android.os.Process.myPid()},nativeStep=$nativeTokenSteps," +
                            "ttftUs=$ttftUs,afterPrefillUs=$afterPrefillUs," +
                            "firstPieceChars=${tokenPiece.length},backend=${modelInfo.activeBackend}"
                    )
                }
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
                    val pieceFailure = InferenceOutputGate.visibleTextFailure(tokenPiece)
                    if (pieceFailure != null) {
                        EngineDiagnostics.info(
                            "inference",
                            "OUTPUT_GATE_RESULT",
                            "runId=$inferenceRunId,stage=STREAM,status=FAIL,reason=$pieceFailure," +
                                "tokenIndex=$nativeTokenSteps"
                        )
                        throw IllegalStateException("Generated token piece failed output gate ($pieceFailure)")
                    }
                    if (!firstVisibleTokenRecorded) {
                        firstVisibleTokenRecorded = true
                        val latencyAfterPrefillMs =
                            (System.nanoTime() - prefillCompletedNano) / 1_000_000L
                        EngineDiagnostics.info(
                            "inference",
                            "FIRST_TOKEN_RECEIVED",
                            "runId=$inferenceRunId,pid=${android.os.Process.myPid()}, " +
                                "latencyAfterPrefillMs=$latencyAfterPrefillMs,ttftUs=$ttftUs,backend=${modelInfo.activeBackend}"
                        )
                    }
                    outputBuilder.append(tokenPiece)
                    val tokenSend = trySend(InferenceEvent.Token(tokenPiece, tokenCount))
                    if (tokenSend.isFailure) {
                        isCancelled.set(true)
                        NativeEngineHandle.nativeCancel(handle)
                        throw CancellationException("Token collector is no longer active")
                    }
                    tokenCount++
                    // The native engine enforces maxPredictTokens using generated token IDs.
                    // Do not stop by counting visible chunks here: one native token may emit
                    // no text while completing a split UTF-8 code point. Let the next native
                    // step set MAX_TOKENS and flush any pending UTF-8 suffix before returning null.
                }
            }

            // Cancellation can arrive between two JNI token steps. In that case the
            // while condition exits before nativeGenerateNextToken observes the cancel
            // flag and flushes a pending UTF-8 suffix. Force one final native step; with
            // native cancellation set it only finalizes the stream and cannot sample.
            if (isCancelled.get()) {
                finalizePendingNativeOutput()
            }

            val rawReason = NativeEngineHandle.nativeGetLastFinishReason(handle)
            val finishReason = NativeFinishReasonMapper.fromNativeCode(
                rawReason = rawReason,
                cancellationRequested = isCancelled.get()
            )

            val durationUs = NativeEngineHandle.nativeGetInferenceDurationUs(handle)
            val durationMs = (durationUs / 1000L).coerceAtLeast(1L)
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            val tokensPerSec = (actualTokens * 1000.0) / durationMs
            val fullText = outputBuilder.toString()
            EngineDiagnostics.info(
                "inference",
                "GENERATION_COMPLETED",
                "runId=$inferenceRunId,pid=${android.os.Process.myPid()},tokens=$actualTokens, " +
                    "generationMs=$durationMs,decodeUs=$durationUs,tokensPerSecond=$tokensPerSec,finishReason=$finishReason, " +
                    "backend=${modelInfo.activeBackend}"
            )
            logInferencePhaseMetrics("COMPLETED", finishReason.name, actualTokens, durationUs)
            EngineDiagnostics.recordInferenceOutput(
                runId = inferenceRunId,
                text = fullText,
                finishReason = finishReason.name,
                tokensGenerated = actualTokens
            )

            val outputGate = InferenceOutputGate.evaluate(fullText, actualTokens, finishReason)
            val outputGateStatus = when {
                finishReason == FinishReason.CANCELLED -> "NOT_APPLICABLE"
                !outputGate.accepted -> "FAIL"
                outputGate.truncated -> "TRUNCATED"
                else -> "PASS"
            }
            EngineDiagnostics.info(
                "inference",
                "OUTPUT_GATE_RESULT",
                "runId=$inferenceRunId,stage=FINAL,status=$outputGateStatus," +
                    "reason=${outputGate.reason ?: "NONE"},finishReason=$finishReason," +
                    "tokens=$actualTokens,chars=${fullText.length}"
            )

            when (finishReason) {
                FinishReason.CANCELLED -> trySend(InferenceEvent.Cancelled(fullText, actualTokens))
                else -> {
                    if (!outputGate.accepted) {
                        val error = IllegalStateException(
                            "Generated output failed correctness gate " +
                                "(reason=${outputGate.reason},tokens=$actualTokens,finishReason=$finishReason)"
                        )
                        EngineDiagnostics.error("inference", "OUTPUT_GATE_FAILED", error)
                        trySend(InferenceEvent.Error(error, fullText))
                    } else {
                        val result = InferenceResult(
                            text = fullText,
                            tokensGenerated = actualTokens,
                            durationMs = durationMs,
                            tokensPerSecond = tokensPerSec,
                            finishReason = finishReason,
                            graphSplits = graphSplits,
                            diagnosticRunId = inferenceRunId,
                            nativeRunId = inferenceRunId
                        )
                        trySend(InferenceEvent.Completed(result))
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "streamChat coroutine cancelled, finalizing pending native UTF-8 output")
            finalizePendingNativeOutput()
            val actualTokens = NativeEngineHandle.nativeGetTokensGenerated(handle).coerceAtLeast(tokenCount)
            logInferencePhaseMetrics(
                "GENERATION_CANCELLED",
                "CANCELLED",
                actualTokens,
                NativeEngineHandle.nativeGetInferenceDurationUs(handle).coerceAtLeast(0L)
            )
            EngineDiagnostics.recordInferenceOutput(
                inferenceRunId, outputBuilder.toString(), "CANCELLED", actualTokens
            )
            EngineDiagnostics.info(
                "inference", "INFERENCE_TERMINAL_FAILURE",
                "runId=$inferenceRunId,phase=GENERATION,backend=${modelInfo.activeBackend},finishReason=CANCELLED"
            )
            trySend(InferenceEvent.Cancelled(outputBuilder.toString(), actualTokens))
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error during generation loop", t)
            EngineDiagnostics.info(
                "inference", "INFERENCE_TERMINAL_FAILURE",
                "runId=$inferenceRunId,phase=GENERATION,backend=${modelInfo.activeBackend},outputChars=${outputBuilder.length}"
            )
            finalizePendingNativeOutput()
            logInferencePhaseMetrics(
                "GENERATION_FAILED",
                "ERROR",
                tokenCount,
                NativeEngineHandle.nativeGetInferenceDurationUs(handle).coerceAtLeast(0L)
            )
            EngineDiagnostics.recordInferenceOutput(
                inferenceRunId, outputBuilder.toString(), "ERROR", tokenCount
            )
            EngineDiagnostics.error("inference", "GENERATION_LOOP_FAILED", t)
            trySend(InferenceEvent.Error(t, outputBuilder.toString()))
        } finally {
            nativeWorkActive.set(false)
            progressEvents.close()
            isCancelled.set(false)
            _state.value = EngineState.ModelLoaded(modelInfo)
            close()
        }
    }.buffer(Channel.UNLIMITED).flowOn(llamaDispatcher)

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
        }.onFailure { error ->
            if (error is CancellationException) throw error
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

    private fun logNativeModelResourceState(
        handle: Long,
        stage: String,
        loadResult: Int? = null
    ) {
        val state = runCatching {
            NativeEngineHandle.nativeGetEngineResourceDiagnostics(handle)
        }.getOrElse { error ->
            "diagnosticsError=${error.javaClass.simpleName}"
        }
        val loadResultField = loadResult?.let { ",loadResult=$it" }.orEmpty()
        EngineDiagnostics.info(
            "native",
            "NATIVE_MODEL_RESOURCE_STATE",
            "stage=$stage$loadResultField,$state"
        )
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
    private fun trustedImportedSha256(file: File): String? =
        ModelFileNamePolicy.trustedPrivateImportSha256(
            file = file,
            privateModelsDir = File(context.filesDir, "models")
        )

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
