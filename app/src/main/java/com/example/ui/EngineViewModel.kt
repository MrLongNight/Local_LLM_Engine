package com.example.ui

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.InferenceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppTab {
    CHAT,
    IMPORT,
    SETTINGS
}

data class ImportedModelEntry(
    val file: File,
    val name: String,
    val sizeBytes: Long
) {
    val sizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)
}

class EngineViewModel(
    application: Application,
    val engine: LocalLlmEngine
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        LocalLlmEngine.create(application)
    )

    companion object {
        private const val TAG = "EngineViewModel"
        const val DEFAULT_SYSTEM_PROMPT =
            "Du bist ein hilfreicher, präziser KI-Assistent."
    }

    private val _currentTab = MutableStateFlow(AppTab.CHAT)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    val engineState: StateFlow<EngineState> = engine.state
    val currentModel: StateFlow<ModelInfo?> = engine.currentModel
    val backendInfo: StateFlow<BackendInfo> = engine.backendInfo
    val modelLoadProgress: StateFlow<Int?> = engine.modelLoadProgress

    // UI welcome copy is rendered by ChatScreen while this list is empty.
    // It must never be sent to the model as a synthetic assistant turn.
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentStreamingText = MutableStateFlow("")
    val currentStreamingText: StateFlow<String> = _currentStreamingText.asStateFlow()

    private val _inferenceProgress = MutableStateFlow<InferenceEvent.Progress?>(null)
    val inferenceProgress: StateFlow<InferenceEvent.Progress?> = _inferenceProgress.asStateFlow()

    private val _engineConfig = MutableStateFlow(
        EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
            temperature = 0.7f,
            topP = 0.9f,
            maxPredictTokens = 512,
            backendPreference = BackendPreference.VULKAN,
            // Qwen3-4B has 36 transformer layers plus the output layer.
            // 37 requests full offload for that class of model; llama.cpp caps
            // the value to the actual layer count for smaller models.
            gpuLayers = 37
        )
    )
    val engineConfig: StateFlow<EngineConfig> = _engineConfig.asStateFlow()

    private val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _importedModelEntries = MutableStateFlow<List<ImportedModelEntry>>(emptyList())
    val importedModelEntries: StateFlow<List<ImportedModelEntry>> = _importedModelEntries.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    private val _benchmarkReport = MutableStateFlow<String?>(null)
    val benchmarkReport: StateFlow<String?> = _benchmarkReport.asStateFlow()

    private val _isConfigSmokeTesting = MutableStateFlow(false)
    val isConfigSmokeTesting: StateFlow<Boolean> = _isConfigSmokeTesting.asStateFlow()

    private val _configSmokeReport = MutableStateFlow<String?>(null)
    val configSmokeReport: StateFlow<String?> = _configSmokeReport.asStateFlow()

    private var configurationSmokeJob: Job? = null
    private var inferenceJob: Job? = null
    private var generationSequence: Long = 0L
    private val lastModelStore = LastModelStore(application)

    init {
        refreshModelFiles()
        restoreLastModelOnStartup()
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
        EngineDiagnostics.info("ui", "TAB_SELECTED", "tab=$tab")
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun updateSystemPrompt(prompt: String) {
        if (_isBenchmarking.value) return
        _systemPrompt.value = prompt
    }

    fun updateConfig(config: EngineConfig) {
        if (_isBenchmarking.value) return
        val previous = _engineConfig.value
        _engineConfig.value = config
        if (previous.backendPreference != config.backendPreference ||
            previous.gpuLayers != config.gpuLayers ||
            previous.vulkanMatvecWorkgroup != config.vulkanMatvecWorkgroup ||
            previous.flashAttentionMode != config.flashAttentionMode ||
            previous.reloadAfterAbort != config.reloadAfterAbort
        ) {
            EngineDiagnostics.info(
                "ui",
                "EXECUTION_CONFIG_UPDATED",
                "backendPreference=${config.backendPreference}, gpuLayers=${config.gpuLayers}, " +
                    "vulkanMatvecWorkgroup=${config.vulkanMatvecWorkgroup}, " +
                    "flashAttentionMode=${config.flashAttentionMode}, reloadAfterAbort=${config.reloadAfterAbort}"
            )
        }
    }

    fun dismissStatus() {
        _statusMessage.value = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun refreshModelFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val modelsDir = File(getApplication<Application>().filesDir, "models")
                if (modelsDir.exists()) {
                    val files = modelsDir.listFiles { f ->
                        f.isFile && f.name.endsWith(".gguf", ignoreCase = true)
                    }?.sortedByDescending { it.lastModified() } ?: emptyList()

                    val entries = files.map { file ->
                        ImportedModelEntry(
                            file = file,
                            name = file.name,
                            sizeBytes = file.length()
                        )
                    }
                    _importedModelEntries.value = entries
                } else {
                    _importedModelEntries.value = emptyList()
                }
            }
        }
    }

    /**
     * All model-load entry points share one state/error/progress lifecycle. Only the
     * source adapter differs between a selected private file and a SAF content URI.
     */
    private fun startModelLoad(source: ModelSource, operationLabel: String) {
        if (_isBenchmarking.value) {
            _errorMessage.value = "Während des Benchmarks sind Modellwechsel gesperrt."
            return
        }
        if (_isImporting.value) {
            EngineDiagnostics.info("ui", "MODEL_LOAD_REQUEST_IGNORED", "A model operation is already active")
            return
        }

        _isImporting.value = true
        _errorMessage.value = null
        _statusMessage.value = operationLabel
        // Capture the exact settings at the user's load action. A later slider/chip
        // change must not silently alter a queued model-load operation.
        val loadConfig = _engineConfig.value
        EngineDiagnostics.info(
            "ui",
            "MODEL_LOAD_CONFIGURATION",
            "operation=$operationLabel, backendPreference=${loadConfig.backendPreference}, " +
                "gpuLayers=${loadConfig.gpuLayers}, context=${loadConfig.contextSize}, " +
                "batch=${loadConfig.batchSize}, threads=${loadConfig.threads}"
        )

        viewModelScope.launch {
            try {
                val result = engine.loadModel(source, loadConfig)
                result.onSuccess { info ->
                    runCatching { lastModelStore.save(source, loadConfig, info.name) }
                        .onSuccess {
                            EngineDiagnostics.info(
                                "model_restore",
                                "LAST_MODEL_SAVED",
                                "sourceType=${modelSourceKind(source)}, name=${info.name}, configPersisted=true"
                            )
                        }
                        .onFailure { error ->
                            EngineDiagnostics.error("model_restore", "LAST_MODEL_SAVE_FAILED", error)
                        }
                    refreshModelFiles()
                    val hashLabel = info.sha256?.take(12)?.let { " (SHA-256: $it…)" }.orEmpty()
                    _statusMessage.value = "Modell '${info.name}' bereit (${info.activeBackend})$hashLabel"
                    _errorMessage.value = null
                    EngineDiagnostics.info(
                        "ui",
                        "MODEL_READY",
                        "name=${info.name}, backend=${info.activeBackend}, sha256Present=${!info.sha256.isNullOrBlank()}"
                    )
                }.onFailure { error ->
                    EngineDiagnostics.error("ui", "MODEL_LOAD_UI_FAILURE", error)
                    _errorMessage.value = "Modell konnte nicht geladen werden: ${error.message ?: error.javaClass.simpleName}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                EngineDiagnostics.error("ui", "MODEL_LOAD_UI_EXCEPTION", error)
                _errorMessage.value = "Modell konnte nicht geladen werden: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importModelFromSaf(uri: Uri) {
        // Auto-restore requires a persisted read grant, not the temporary picker grant.
        val permissionResult = runCatching {
            val resolver = getApplication<Application>().contentResolver
            val alreadyPersisted = resolver.persistedUriPermissions.any {
                it.uri.toString() == uri.toString() && it.isReadPermission
            }
            if (!alreadyPersisted) {
                resolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val persisted = resolver.persistedUriPermissions.any {
                it.uri.toString() == uri.toString() && it.isReadPermission
            }
            check(persisted) { "Android did not retain a persistent read grant for this document." }
        }
        permissionResult.onFailure { error ->
            EngineDiagnostics.error("model_restore", "SAF_PERSIST_PERMISSION_FAILED", error)
            _errorMessage.value =
                "Für den automatischen Modellstart konnte Android keinen dauerhaften Lesezugriff speichern. " +
                "Bitte wähle die GGUF-Datei über „Modell-Import“ erneut aus."
        }
        if (permissionResult.isFailure) return

        startModelLoad(
            source = ModelSource.ContentUri(uriString = uri.toString()),
            operationLabel = "Öffne GGUF-Datei direkt am Originalspeicherort..."
        )
    }

    /**
     * Restores the last successfully loaded model once when a fresh ViewModel starts.
     * SAF sources are reopened only when Android still holds the persisted read grant.
     */
    private fun restoreLastModelOnStartup() {
        val remembered = runCatching { lastModelStore.load() }.getOrElse { error ->
            EngineDiagnostics.error("model_restore", "LAST_MODEL_READ_FAILED", error)
            _errorMessage.value = "Die gespeicherten Modellinformationen sind beschädigt. Bitte wähle das Modell erneut aus."
            return
        } ?: return

        _engineConfig.value = remembered.config
        val source = remembered.source
        val displayName = when (source) {
            is ModelSource.FilePath -> File(source.path).name
            is ModelSource.ContentUri -> source.displayName ?: "zuletzt verwendete SAF-Datei"
            else -> {
                EngineDiagnostics.info("model_restore", "LAST_MODEL_SOURCE_UNSUPPORTED")
                _errorMessage.value = "Das zuletzt verwendete Modell kann nicht automatisch geöffnet werden. Bitte wähle es erneut aus."
                return
            }
        }

        when (source) {
            is ModelSource.FilePath -> {
                if (!File(source.path).isFile) {
                    EngineDiagnostics.info(
                        "model_restore",
                        "LAST_MODEL_FILE_MISSING",
                        "name=$displayName"
                    )
                    _errorMessage.value =
                        "Das zuletzt verwendete Modell „$displayName“ wurde nicht gefunden. Bitte wähle die GGUF-Datei erneut aus."
                    return
                }
            }
            is ModelSource.ContentUri -> {
                val hasPersistedReadGrant = runCatching {
                    getApplication<Application>().contentResolver.persistedUriPermissions.any {
                        it.uri.toString() == source.uriString && it.isReadPermission
                    }
                }.getOrDefault(false)
                if (!hasPersistedReadGrant) {
                    EngineDiagnostics.info(
                        "model_restore",
                        "LAST_MODEL_URI_PERMISSION_MISSING",
                        "name=$displayName"
                    )
                    _errorMessage.value =
                        "Der dauerhafte Zugriff auf „$displayName“ ist nicht mehr vorhanden. Bitte wähle die GGUF-Datei erneut aus."
                    return
                }
            }
            else -> return
        }

        EngineDiagnostics.info(
            "model_restore",
            "LAST_MODEL_AUTO_RESTORE_STARTED",
            "sourceType=${modelSourceKind(source)}, name=$displayName"
        )
        startModelLoad(
            source = source,
            operationLabel = "Lade zuletzt verwendetes Modell: $displayName…"
        )
    }

    private fun modelSourceKind(source: ModelSource): String = when (source) {
        is ModelSource.FilePath -> "APP_FILE"
        is ModelSource.ContentUri -> "SAF_URI"
        is ModelSource.AssetPath -> "ASSET"
        is ModelSource.RemoteUrl -> "REMOTE_URL"
    }

    fun loadModelFile(file: File) {
        startModelLoad(
            source = ModelSource.FilePath(file.absolutePath),
            operationLabel = "Lade Modell: ${file.name}..."
        )
    }

    fun unloadModel() {
        if (_isBenchmarking.value) {
            _errorMessage.value = "Während des Benchmarks kann das Modell nicht entladen werden."
            return
        }
        viewModelScope.launch {
            engine.unloadModel()
                .onSuccess {
                    _statusMessage.value = "Modell entladen"
                    _errorMessage.value = null
                }
                .onFailure { error ->
                    _statusMessage.value = null
                    _errorMessage.value = "Modell konnte nicht entladen werden: ${error.message ?: error.javaClass.simpleName}"
                }
        }
    }


    /**
     * Short, reproducible comparison. Each backend gets one model load and one inference
     * request. The first four generated tokens warm the execution path; the following
     * twenty are measured. This avoids repeating the expensive prompt prefill twice.
     */
    fun runDefaultBackendBenchmark() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Benchmark erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }

        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val benchmarkPrompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."
        val benchmarkConfig = BackendBenchmarkPlan.configuration(originalConfig)
        val backends = BackendBenchmarkPlan.BACKENDS

        _isBenchmarking.value = true
        _benchmarkReport.value = "Benchmark wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val lines = mutableListOf<String>()
            EngineDiagnostics.info(
                "benchmark",
                "BACKEND_BENCHMARK_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},prompt=fixed_counting_sequence," +
                    "backendOrder=VULKAN,CPU,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},totalTokens=${BackendBenchmarkPlan.TOTAL_TOKENS}," +
                    "context=${benchmarkConfig.contextSize},batch=${benchmarkConfig.batchSize},threads=${benchmarkConfig.threads}," +
                    "batchThreads=${benchmarkConfig.effectiveBatchThreads},microBatch=${benchmarkConfig.effectiveMicroBatchSize}," +
                    "gpuLayers=${benchmarkConfig.gpuLayers},temperature=0.0,topP=1.0"
            )

            try {
                for ((index, backend) in backends.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    _benchmarkReport.value = "Lade ${backend.name} für den kurzen Vergleich…"
                    try {
                        val backendConfig = benchmarkConfig.copy(backendPreference = backend)
                        val loadStarted = System.nanoTime()
                        val loaded = engine.loadModel(source, backendConfig).getOrElse { throw it }
                        val modelLoadMs = (System.nanoTime() - loadStarted) / 1_000_000L

                        val actualBackend = backendFamily(loaded.activeBackend)
                        if (actualBackend != backend.name) {
                            throw IllegalStateException(
                                "Angefordert wurde ${backend.name}, geladen wurde aber ${loaded.activeBackend}"
                            )
                        }
                        if (loaded.sizeBytes != model.sizeBytes ||
                            (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                        ) {
                            throw IllegalStateException("Nach dem Neuladen wurde nicht dieselbe Modelldatei bestätigt.")
                        }

                        val measured = collectBackendBenchmarkSample(
                            backend = backend,
                            maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                            warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                            systemPromptSnapshot = systemPromptSnapshot,
                            benchmarkPrompt = benchmarkPrompt
                        )
                        if (measured.measuredTokens < 8) {
                            throw IllegalStateException(
                                "Zu wenige Mess-Tokens nach dem Warm-up: ${measured.measuredTokens}"
                            )
                        }

                        val reportLine = String.format(
                            java.util.Locale.US,
                            "%s: modelLoadMs=%d, promptTokens=%d, prefillMs=%s, firstTokenAfterPrefillMs=%s, " +
                                "warmupTokens=%d, warmupWallMs=%s, measuredTokens=%d, measuredWallMs=%s, " +
                                "measuredTokensPerSecond=%.3f, nativeGenerationMs=%d, nativeTokensPerSecond=%.3f, " +
                                "streamWallMs=%d, finishReason=%s, activeBackend=%s",
                            backend.name,
                            modelLoadMs,
                            measured.promptTokens,
                            measured.promptEvaluationMs?.toString() ?: "n/a",
                            measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                            measured.warmupTokens,
                            measured.warmupWallMs?.toString() ?: "n/a",
                            measured.measuredTokens,
                            measured.measuredWallMs?.toString() ?: "n/a",
                            measured.measuredTokensPerSecond,
                            measured.result.durationMs,
                            measured.result.tokensPerSecond,
                            measured.wallMs,
                            measured.result.finishReason.name,
                            loaded.activeBackend
                        )
                        lines.add(reportLine)
                        EngineDiagnostics.info(
                            "benchmark",
                            "BACKEND_BENCHMARK_RESULT",
                            reportLine + ",context=${backendConfig.contextSize},batch=${backendConfig.batchSize}," +
                                "threads=${backendConfig.threads},batchThreads=${backendConfig.effectiveBatchThreads}," +
                                "microBatch=${backendConfig.effectiveMicroBatchSize},gpuLayers=${backendConfig.gpuLayers}," +
                                "temperature=0.0,topP=1.0,maxPredictTokens=${BackendBenchmarkPlan.TOTAL_TOKENS}"
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val failure = "${backend.name}: FEHLER - ${error.message ?: error.javaClass.simpleName}"
                        lines.add(failure)
                        EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_FAILED", error)
                    }

                    if (index < backends.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                lines.add("Benchmark wurde abgebrochen.")
                throw cancelled
            } finally {
                _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                val restore = withContext(NonCancellable) {
                    runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                }
                restore.onSuccess { restored ->
                    EngineDiagnostics.info(
                        "benchmark",
                        "BACKEND_BENCHMARK_RESTORED",
                        "backendPreference=${originalConfig.backendPreference},activeBackend=${restored.activeBackend}"
                    )
                }.onFailure { error ->
                    lines.add("Wiederherstellung der ursprünglichen Konfiguration fehlgeschlagen: ${error.message}")
                    EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_RESTORE_FAILED", error)
                }

                _benchmarkReport.value = buildString {
                    appendLine("CPU- / Vulkan-Kurzbenchmark")
                    appendLine("Test: Zahlenfolge 1–40")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine(
                        "Pro Backend: eine Inferenz, 4 Warm-up-Tokens + 20 Mess-Tokens; Temperatur 0; Top-P 1; " +
                            "Kontext ${benchmarkConfig.contextSize}; Batch ${benchmarkConfig.batchSize}; " +
                            "${benchmarkConfig.threads} Threads; GPU-Layer ${benchmarkConfig.gpuLayers}."
                    )
                    lines.forEach { appendLine(it) }
                    append("Fortschritt und native Zeitmesswerte stehen zusätzlich im Diagnoseexport.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    private data class BackendBenchmarkSample(
        val result: InferenceResult,
        val firstTokenAfterPrefillMs: Long?,
        val promptEvaluationMs: Long?,
        val promptTokens: Int,
        val warmupTokens: Int,
        val warmupWallMs: Long?,
        val measuredTokens: Int,
        val measuredWallMs: Long?,
        val measuredTokensPerSecond: Double,
        val wallMs: Long
    )

    private suspend fun collectBackendBenchmarkSample(
        backend: BackendPreference,
        maxTokens: Int,
        warmupTokens: Int,
        systemPromptSnapshot: String,
        benchmarkPrompt: String
    ): BackendBenchmarkSample {
        val messages = buildList {
            if (systemPromptSnapshot.isNotBlank()) add(ChatMessage.system(systemPromptSnapshot))
            add(ChatMessage.user(benchmarkPrompt))
        }
        val request = InferenceRequest(
            messages = messages,
            maxPredictTokens = maxTokens,
            temperature = 0f,
            topP = 1f
        )

        var firstTokenAfterPrefillMs: Long? = null
        var promptEvaluationMs: Long? = null
        var promptTokens = 0
        var visibleTokenCount = 0
        var warmupWallMs: Long? = null
        var completed: InferenceResult? = null
        val started = System.nanoTime()

        engine.streamChat(request).collect { event ->
            when (event) {
                is InferenceEvent.Progress -> {
                    if (event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION) {
                        promptTokens = event.promptTokensTotal
                        _benchmarkReport.value =
                            "${backend.name}: Prompt ${event.promptTokensProcessed}/${event.promptTokensTotal} Tokens…"
                    } else if (event.phase == InferenceEvent.ProgressPhase.GENERATING) {
                        _benchmarkReport.value =
                            "${backend.name}: ${event.tokensGenerated}/$maxTokens Tokens werden erzeugt…"
                    }
                }
                is InferenceEvent.Started -> {
                    promptEvaluationMs = (System.nanoTime() - started) / 1_000_000L
                    _benchmarkReport.value = "${backend.name}: Prompt abgeschlossen – Generierung läuft…"
                }
                is InferenceEvent.Token -> {
                    visibleTokenCount++
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (firstTokenAfterPrefillMs == null) {
                        firstTokenAfterPrefillMs =
                            (elapsedMs - (promptEvaluationMs ?: elapsedMs)).coerceAtLeast(0L)
                    }
                    if (visibleTokenCount == warmupTokens) {
                        warmupWallMs = elapsedMs
                    }
                    val phase = if (visibleTokenCount <= warmupTokens) "Warm-up" else "Messung"
                    val measuredSoFar = (visibleTokenCount - warmupTokens).coerceAtLeast(0)
                    _benchmarkReport.value =
                        "${backend.name}: $phase – ${visibleTokenCount}/$maxTokens Tokens " +
                            "(${measuredSoFar}/${BackendBenchmarkPlan.MEASURED_TOKENS} gemessen)…"
                }
                is InferenceEvent.Completed -> completed = event.result
                is InferenceEvent.Error -> throw event.throwable
                is InferenceEvent.Cancelled -> throw CancellationException(
                    "${backend.name} wurde abgebrochen."
                )
            }
        }

        val result = completed ?: throw IllegalStateException("Keine abgeschlossene Antwort für ${backend.name} erhalten.")
        val visibleCount = visibleTokenCount.coerceAtMost(result.tokensGenerated)
        val measuredTokens = (result.tokensGenerated - warmupTokens).coerceAtLeast(0)
        val wallMs = (System.nanoTime() - started) / 1_000_000L
        val measuredWallMs = if (measuredTokens > 0 && warmupWallMs != null) {
            (wallMs - warmupWallMs!!).coerceAtLeast(1L)
        } else null
        val measuredSpeed = if (measuredWallMs != null) {
            measuredTokens * 1000.0 / measuredWallMs
        } else 0.0

        return BackendBenchmarkSample(
            result = result,
            firstTokenAfterPrefillMs = firstTokenAfterPrefillMs,
            promptEvaluationMs = promptEvaluationMs,
            promptTokens = promptTokens,
            warmupTokens = minOf(result.tokensGenerated, warmupTokens),
            warmupWallMs = warmupWallMs,
            measuredTokens = measuredTokens,
            measuredWallMs = measuredWallMs,
            measuredTokensPerSecond = measuredSpeed,
            wallMs = wallMs
        )
    }

    /**
     * Measures Vulkan with several layer-offload levels on the same model and prompt.
     * The first load requests the maximum (99) and reads the model's actual layer count
     * from the native runtime string. Each following run changes only gpuLayers.
     * A final full-offload run checks for obvious thermal/frequency drift.
     */
    fun runVulkanLayerSweep() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Vulkan-Layer-Test erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }

        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val prompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."

        _isBenchmarking.value = true
        _benchmarkReport.value = "Vulkan-GPU-Layer-Test wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedTotalLayers: Int? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "benchmark",
                "GPU_LAYER_SWEEP_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},requestedMaxGpuLayers=${VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS}," +
                    "fractions=100,75,50,25,100_repeat,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},context=${originalConfig.contextSize}," +
                    "batch=${originalConfig.batchSize},threads=${originalConfig.threads},temperature=0.0,topP=1.0"
            )

            try {
                val initialSafetyStop = configurationTestSafetyBlock()
                if (initialSafetyStop != null) {
                    throw IllegalStateException("Sicherheitsabbruch vor dem ersten GPU-Layer-Lauf: " + initialSafetyStop)
                }

                restoreRequired = true
                _benchmarkReport.value = "Vulkan: Lade Modell mit maximalem Layer-Offload…"
                val firstConfig = VulkanLayerSweepPlan.configuration(
                    originalConfig,
                    VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                )
                val firstLoadStarted = System.nanoTime()
                val firstLoaded = engine.loadModel(source, firstConfig).getOrElse { throw it }
                val firstLoadMs = (System.nanoTime() - firstLoadStarted) / 1_000_000L

                val firstBackend = backendFamily(firstLoaded.activeBackend)
                if (firstBackend != "VULKAN") {
                    throw IllegalStateException(
                        "Vulkan wurde angefordert, geladen wurde aber: " + firstLoaded.activeBackend
                    )
                }
                if (firstLoaded.sizeBytes != model.sizeBytes ||
                    (!model.sha256.isNullOrBlank() && !firstLoaded.sha256.isNullOrBlank() &&
                        model.sha256 != firstLoaded.sha256)
                ) {
                    throw IllegalStateException("Nach dem maximalen Vulkan-Load wurde nicht dieselbe Modelldatei bestätigt.")
                }

                val totalLayers = VulkanLayerSweepPlan.totalLayersFromBackend(firstLoaded.activeBackend)
                    ?: throw IllegalStateException(
                        "Die native Backend-Meldung enthält keine bestätigte Gesamtzahl der GPU-Layer: " +
                            firstLoaded.activeBackend
                    )
                confirmedTotalLayers = totalLayers
                val candidateLayers = VulkanLayerSweepPlan.layerCounts(totalLayers)
                val runLayers = candidateLayers + candidateLayers.first()
                EngineDiagnostics.info(
                    "benchmark",
                    "GPU_LAYER_SWEEP_PLAN",
                    "totalLayers=$totalLayers,candidateLayers=" + candidateLayers.joinToString(",") +
                        ",repeatFullOffload=true,activeBackend=${firstLoaded.activeBackend}"
                )

                for ((index, gpuLayers) in runLayers.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val safetyStop = if (index == 0) null else configurationTestSafetyBlock()
                    if (safetyStop != null) {
                        stoppedReason = safetyStop
                        rows += "ABBRUCH vor gpuLayers=$gpuLayers: $safetyStop"
                        EngineDiagnostics.info("benchmark", "GPU_LAYER_SWEEP_SAFETY_STOP", safetyStop)
                        break
                    }

                    val profile = when {
                        index == 0 -> "100% (Start)"
                        index == runLayers.lastIndex -> "100% (Wiederholung)"
                        else -> "${(gpuLayers * 100.0 / totalLayers).toInt()}%"
                    }
                    _benchmarkReport.value =
                        "Vulkan-Layer-Test ${index + 1}/${runLayers.size}: $gpuLayers/$totalLayers Layer ($profile)…"

                    val loadMs: Long
                    val loaded: ModelInfo
                    val configuredGpuLayers: Int
                    if (index == 0) {
                        loadMs = firstLoadMs
                        loaded = firstLoaded
                        configuredGpuLayers = VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                    } else {
                        // Repeat the exact same maximum request as the opening run. The
                        // native loader clamps both to the model's real total layer count.
                        val requestLayers = if (index == runLayers.lastIndex) {
                            VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                        } else {
                            gpuLayers
                        }
                        val profileConfig = VulkanLayerSweepPlan.configuration(originalConfig, requestLayers)
                        configuredGpuLayers = profileConfig.gpuLayers
                        val loadStarted = System.nanoTime()
                        loaded = engine.loadModel(source, profileConfig).getOrElse { throw it }
                        loadMs = (System.nanoTime() - loadStarted) / 1_000_000L
                    }

                    val actualBackend = backendFamily(loaded.activeBackend)
                    if (actualBackend != "VULKAN") {
                        throw IllegalStateException(
                            "Vulkan-Profil gpuLayers=$gpuLayers wechselte unerwartet auf: " + loaded.activeBackend
                        )
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() &&
                            model.sha256 != loaded.sha256)
                    ) {
                        throw IllegalStateException("Für gpuLayers=$gpuLayers wurde nicht dieselbe Modelldatei bestätigt.")
                    }

                    val measured = collectBackendBenchmarkSample(
                        backend = BackendPreference.VULKAN,
                        maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                        warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                        systemPromptSnapshot = systemPromptSnapshot,
                        benchmarkPrompt = prompt
                    )
                    if (measured.measuredTokens < 8) {
                        throw IllegalStateException(
                            "Zu wenige Mess-Tokens nach dem Warm-up bei gpuLayers=$gpuLayers: " +
                                measured.measuredTokens
                        )
                    }

                    val percent = gpuLayers * 100.0 / totalLayers
                    val reportLine = String.format(
                        java.util.Locale.US,
                        "gpuLayers=%d/%d (%.0f%%): load=%dms, promptTokens=%d, prefillMs=%s, " +
                            "TTFT=%s ms, measuredTokens=%d, measuredWallMs=%s, measured=%.3f tok/s, " +
                            "native=%.3f tok/s, generationMs=%d, finish=%s, activeBackend=%s",
                        gpuLayers,
                        totalLayers,
                        percent,
                        loadMs,
                        measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                        measured.measuredTokens,
                        measured.measuredWallMs?.toString() ?: "n/a",
                        measured.measuredTokensPerSecond,
                        measured.result.tokensPerSecond,
                        measured.result.durationMs,
                        measured.result.finishReason.name,
                        loaded.activeBackend
                    )
                    rows += reportLine
                    EngineDiagnostics.info(
                        "benchmark",
                        "GPU_LAYER_SWEEP_RESULT",
                        "runIndex=${index + 1},profile=$profile,configuredGpuLayers=$configuredGpuLayers," +
                            "layerTarget=$gpuLayers,totalLayers=$totalLayers," +
                            "modelLoadMs=$loadMs,promptTokens=${measured.promptTokens}," +
                            "prefillMs=${measured.promptEvaluationMs ?: -1L}," +
                            "firstTokenAfterPrefillMs=${measured.firstTokenAfterPrefillMs ?: -1L}," +
                            "warmupTokens=${measured.warmupTokens},measuredTokens=${measured.measuredTokens}," +
                            "measuredWallMs=${measured.measuredWallMs ?: -1L}," +
                            "measuredTokensPerSecond=${measured.measuredTokensPerSecond}," +
                            "nativeTokensPerSecond=${measured.result.tokensPerSecond}," +
                            "generationMs=${measured.result.durationMs},finishReason=${measured.result.finishReason.name}," +
                            "context=${originalConfig.contextSize},batch=${originalConfig.batchSize}," +
                            "threads=${originalConfig.threads},batchThreads=${originalConfig.effectiveBatchThreads}," +
                            "microBatch=${originalConfig.effectiveMicroBatchSize},backend=${loaded.activeBackend}"
                    )
                    if (index < runLayers.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                rows += "Vulkan-Layer-Test wurde abgebrochen."
                stoppedReason = "Vom Nutzer abgebrochen."
                EngineDiagnostics.info("benchmark", "GPU_LAYER_SWEEP_CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: $stoppedReason"
                EngineDiagnostics.error("benchmark", "GPU_LAYER_SWEEP_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                    val restore = withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                    }
                    restore.onSuccess { restored ->
                        EngineDiagnostics.info(
                            "benchmark",
                            "GPU_LAYER_SWEEP_RESTORED",
                            "backendPreference=${originalConfig.backendPreference},gpuLayers=${originalConfig.gpuLayers}," +
                                "activeBackend=${restored.activeBackend}"
                        )
                    }.onFailure { error ->
                        rows += "KRITISCH: Restore fehlgeschlagen: " +
                            (error.message ?: error.javaClass.simpleName)
                        EngineDiagnostics.error("benchmark", "GPU_LAYER_SWEEP_RESTORE_FAILED", error)
                    }
                }

                _benchmarkReport.value = buildString {
                    appendLine("Vulkan GPU-Layer-Vergleich")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine("Native bestätigte Layer-Gesamtzahl: ${confirmedTotalLayers ?: "nicht bestätigt"}")
                    appendLine(
                        "Je Profil: eine Inferenz, 4 Warm-up-Tokens + 20 Mess-Tokens; Temperatur 0; Top-P 1; " +
                            "gleiche Prompt- und Kontext-/Batch-/Thread-Einstellungen."
                    )
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: $stoppedReason")
                    append("Die ursprüngliche Modellkonfiguration wurde anschließend wiederhergestellt; Details stehen im Diagnoseexport.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    /**
     * Controlled Vulkan dequantize-matvec workgroup comparison.
     * Every profile reloads the same GGUF with full Vulkan offload and runs the
     * same deterministic one-prefill benchmark: AUTO, SUBGROUP, LARGE, AUTO repeat.
     */
    fun runVulkanQ4kDecodePathSweep() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Q4_K-Kerneltest erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }

        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val profiles = BackendBenchmarkPlan.vulkanQ4kDecodeConfigurations(originalConfig)
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val prompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."

        _isBenchmarking.value = true
        _benchmarkReport.value = "Q4_K-Integer-Dot gegen Basis-DMMV wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedTotalLayers: Int? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "benchmark",
                "VULKAN_Q4K_PATH_SWEEP_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},profiles=MMVQ_INTEGER_DOT,BASE_DMMV,BASE_DMMV_REPEAT,MMVQ_INTEGER_DOT_REPEAT," +
                    "gpuLayersRequested=99,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},context=${originalConfig.contextSize}," +
                    "batch=${originalConfig.batchSize},threads=${originalConfig.threads}," +
                    "batchThreads=${originalConfig.effectiveBatchThreads}," +
                    "microBatch=${originalConfig.effectiveMicroBatchSize},temperature=0.0,topP=1.0"
            )

            try {
                val initialSafetyStop = configurationTestSafetyBlock()
                if (initialSafetyStop != null) {
                    throw IllegalStateException("Sicherheitsabbruch vor dem ersten Q4_K-Kernel-Lauf: $initialSafetyStop")
                }

                restoreRequired = true
                _benchmarkReport.value = "Q4_K Integer-Dot-MMVQ: Lade Modell mit vollem Vulkan-Offload…"
                val firstLoadStarted = System.nanoTime()
                val firstLoaded = engine.loadModel(source, profiles.first()).getOrElse { throw it }
                val firstLoadMs = (System.nanoTime() - firstLoadStarted) / 1_000_000L

                if (backendFamily(firstLoaded.activeBackend) != "VULKAN") {
                    throw IllegalStateException(
                        "Vulkan wurde angefordert, geladen wurde aber: ${firstLoaded.activeBackend}"
                    )
                }
                if (firstLoaded.sizeBytes != model.sizeBytes ||
                    (!model.sha256.isNullOrBlank() && !firstLoaded.sha256.isNullOrBlank() &&
                        model.sha256 != firstLoaded.sha256)
                ) {
                    throw IllegalStateException("Nach dem Q4_K-MMVQ-Load wurde nicht dieselbe Modelldatei bestätigt.")
                }

                val totalLayers = VulkanLayerSweepPlan.totalLayersFromBackend(firstLoaded.activeBackend)
                    ?: throw IllegalStateException(
                        "Die native Backend-Meldung enthält keine bestätigte Gesamtzahl der GPU-Layer: " +
                            firstLoaded.activeBackend
                    )
                confirmedTotalLayers = totalLayers
                EngineDiagnostics.info(
                    "benchmark",
                    "VULKAN_Q4K_PATH_SWEEP_PLAN",
                    "modelLayers=$totalLayers,requestedGpuLayers=99,activeBackend=${firstLoaded.activeBackend}," +
                        "pathOrder=MMVQ_INTEGER_DOT,BASE_DMMV,BASE_DMMV,MMVQ_INTEGER_DOT"
                )

                for ((index, profileConfig) in profiles.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val safetyStop = if (index == 0) null else configurationTestSafetyBlock()
                    if (safetyStop != null) {
                        stoppedReason = safetyStop
                        rows += "ABBRUCH vor ${profileConfig.vulkanMatvecWorkgroup.name}: $safetyStop"
                        EngineDiagnostics.info("benchmark", "VULKAN_Q4K_PATH_SWEEP_SAFETY_STOP", safetyStop)
                        break
                    }

                    val workgroup = profileConfig.vulkanMatvecWorkgroup
                    val baseDmmv = workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV
                    val pathName = if (baseDmmv) "BASE_DMMV" else "MMVQ_INTEGER_DOT"
                    val displayName = when (index) {
                        0 -> "MMVQ Integer-Dot"
                        1 -> "Basis-DMMV"
                        2 -> "Basis-DMMV (Wiederholung)"
                        else -> "MMVQ Integer-Dot (Wiederholung)"
                    }
                    _benchmarkReport.value = "Q4_K-Kerneltest ${index + 1}/${profiles.size}: $displayName…"

                    val loadMs: Long
                    val loaded: ModelInfo
                    if (index == 0) {
                        loadMs = firstLoadMs
                        loaded = firstLoaded
                    } else {
                        val loadStarted = System.nanoTime()
                        loaded = engine.loadModel(source, profileConfig).getOrElse { throw it }
                        loadMs = (System.nanoTime() - loadStarted) / 1_000_000L
                    }

                    if (backendFamily(loaded.activeBackend) != "VULKAN") {
                        throw IllegalStateException(
                            "Profil ${workgroup.name} wechselte unerwartet auf: ${loaded.activeBackend}"
                        )
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() &&
                            model.sha256 != loaded.sha256)
                    ) {
                        throw IllegalStateException("Für ${workgroup.name} wurde nicht dieselbe Modelldatei bestätigt.")
                    }
                    val modelLayers = VulkanLayerSweepPlan.totalLayersFromBackend(loaded.activeBackend)
                    if (modelLayers != totalLayers) {
                        throw IllegalStateException(
                            "Layer-Offload änderte sich für ${workgroup.name}: erwartet $totalLayers, erhalten $modelLayers."
                        )
                    }

                    val measured = collectBackendBenchmarkSample(
                        backend = BackendPreference.VULKAN,
                        maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                        warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                        systemPromptSnapshot = systemPromptSnapshot,
                        benchmarkPrompt = prompt
                    )
                    if (measured.measuredTokens < 8) {
                        throw IllegalStateException(
                            "Zu wenige Mess-Tokens für ${workgroup.name}: ${measured.measuredTokens}"
                        )
                    }

                    val measuredSpeed = measured.measuredTokensPerSecond
                    val reportLine = String.format(
                        java.util.Locale.US,
                        "%s: gpuLayersRequested=%d, modelLayers=%d, load=%dms, promptTokens=%d, prefillMs=%s, " +
                            "TTFT=%s ms, measuredTokens=%d, measuredWallMs=%s, decode=%.3f tok/s, " +
                            "native=%.3f tok/s, generationMs=%d, finish=%s",
                        displayName,
                        profileConfig.gpuLayers,
                        totalLayers,
                        loadMs,
                        measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                        measured.measuredTokens,
                        measured.measuredWallMs?.toString() ?: "n/a",
                        measuredSpeed,
                        measured.result.tokensPerSecond,
                        measured.result.durationMs,
                        measured.result.finishReason.name
                    )
                    rows += reportLine
                    EngineDiagnostics.info(
                        "benchmark",
                        "VULKAN_Q4K_PATH_SWEEP_RESULT",
                        "runIndex=${index + 1},path=$pathName,pathNativeId=${workgroup.nativeId}," +
                            "gpuLayersRequested=${profileConfig.gpuLayers},modelLayers=$modelLayers," +
                            "loadMs=$loadMs,promptTokens=${measured.promptTokens}," +
                            "prefillMs=${measured.promptEvaluationMs ?: -1L}," +
                            "firstTokenAfterPrefillMs=${measured.firstTokenAfterPrefillMs ?: -1L}," +
                            "warmupTokens=${measured.warmupTokens},measuredTokens=${measured.measuredTokens}," +
                            "measuredWallMs=${measured.measuredWallMs ?: -1L}," +
                            "measuredTokensPerSecond=$measuredSpeed," +
                            "nativeTokensPerSecond=${measured.result.tokensPerSecond}," +
                            "generationMs=${measured.result.durationMs}," +
                            "finishReason=${measured.result.finishReason.name}," +
                            "context=${profileConfig.contextSize},batch=${profileConfig.batchSize}," +
                            "threads=${profileConfig.threads},batchThreads=${profileConfig.effectiveBatchThreads}," +
                            "microBatch=${profileConfig.effectiveMicroBatchSize},backend=${loaded.activeBackend}"
                    )
                    if (index < profiles.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                rows += "Q4_K-Kerneltest wurde abgebrochen."
                stoppedReason = "Vom Nutzer abgebrochen."
                EngineDiagnostics.info("benchmark", "VULKAN_Q4K_PATH_SWEEP_CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: $stoppedReason"
                EngineDiagnostics.error("benchmark", "VULKAN_Q4K_PATH_SWEEP_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                    val restore = withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                    }
                    restore.onSuccess { restored ->
                        EngineDiagnostics.info(
                            "benchmark",
                            "VULKAN_Q4K_PATH_SWEEP_RESTORED",
                            "backendPreference=${originalConfig.backendPreference}," +
                                "gpuLayers=${originalConfig.gpuLayers}," +
                                "vulkanMatvecWorkgroup=${originalConfig.vulkanMatvecWorkgroup}," +
                                "activeBackend=${restored.activeBackend}"
                        )
                    }.onFailure { error ->
                        rows += "KRITISCH: Restore fehlgeschlagen: " +
                            (error.message ?: error.javaClass.simpleName)
                        EngineDiagnostics.error("benchmark", "VULKAN_DMMV_SWEEP_RESTORE_FAILED", error)
                    }
                }

                _benchmarkReport.value = buildString {
                    appendLine("Vulkan Q4_K-Decodepfad-Vergleich")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine("Native bestätigte Modell-Layerzahl: ${confirmedTotalLayers ?: "nicht bestätigt"}; je Lauf wurden 99 GPU-Layer angefordert.")
                    appendLine(
                        "Vergleicht Q8_1 Integer-Dot-MMVQ mit Basis-DMMV nur für Q4_K-Decode. " +
                            "Ablauf: MMVQ, Basis-DMMV, Basis-DMMV-Wiederholung, MMVQ-Wiederholung. " +
                            "Je Profil 4 Warm-up- und 20 Mess-Tokens; Temperatur 0; Top-P 1; voller Vulkan-Offload."
                    )
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: $stoppedReason")
                    append("Die nativen Q4_K_DECODE_PATH-Meldungen bestätigen den tatsächlich aktiven Shaderpfad; " +
                        "die ursprüngliche Modellkonfiguration wurde anschließend wiederhergestellt.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    /**
     * Short on-device screening of independent llama.cpp context parameters.
     * The same model/backend and fixed prompt are used for every profile. Context
     * settings require a model reload; original settings are restored in finally.
     * Prompt and generated response content are never written to diagnostics.
     */
    fun runConfigurationSmokeTest() {
        if (_isBenchmarking.value || _isConfigSmokeTesting.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Konfigurationstest erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }
        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val profiles = InferenceConfigSmokePlan.create(
            originalConfig,
            Runtime.getRuntime().availableProcessors()
        )
        if (profiles.size < 2) {
            _errorMessage.value = "Keine abweichenden Testprofile für diese Konfiguration gefunden."
            return
        }

        _isBenchmarking.value = true
        _isConfigSmokeTesting.value = true
        _configSmokeReport.value = "Konfigurationstest wird vorbereitet…"
        _errorMessage.value = null

        configurationSmokeJob = viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedBackend: String? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "config_test",
                "CONFIG_SMOKE_STARTED",
                "model=" + model.name + ",bytes=" + model.sizeBytes +
                    ",profiles=" + profiles.size + ",context=" + originalConfig.contextSize +
                    ",batch=" + originalConfig.batchSize + ",threads=" + originalConfig.threads +
                    ",backend=" + originalConfig.backendPreference + ",gpuLayers=" + originalConfig.gpuLayers
            )
            try {
                for ((index, profile) in profiles.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val stop = configurationTestSafetyBlock()
                    if (stop != null) {
                        stoppedReason = stop
                        rows += "ABBRUCH vor " + profile.id + ": " + stop
                        EngineDiagnostics.info("config_test", "CONFIG_SMOKE_SAFETY_STOP", stop)
                        break
                    }

                    _configSmokeReport.value =
                        "Profil " + (index + 1) + "/" + profiles.size + ": " + profile.label + " – Modell wird neu geladen…"
                    restoreRequired = true
                    val loadStart = System.nanoTime()
                    val loaded = engine.loadModel(source, profile.config).getOrElse { throw it }
                    val loadMs = (System.nanoTime() - loadStart) / 1_000_000L
                    val backend = backendFamily(loaded.activeBackend)
                    if (backend == "UNKNOWN") throw IllegalStateException("Aktives Backend nicht eindeutig: " + loaded.activeBackend)
                    if (confirmedBackend == null) confirmedBackend = backend
                    else if (confirmedBackend != backend) {
                        throw IllegalStateException("Backendwechsel von " + confirmedBackend + " zu " + backend + "; Vergleich abgebrochen.")
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                    ) throw IllegalStateException("Nach Neuladen wurde nicht dieselbe Modelldatei bestätigt.")

                    val warmup = collectConfigurationSmokeSample(InferenceConfigSmokePlan.WARMUP_TOKENS)
                    delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                    val measured = collectConfigurationSmokeSample(InferenceConfigSmokePlan.MEASURED_TOKENS)
                    val cfg = profile.config
                    val report = String.format(
                        java.util.Locale.US,
                        "%s | backend=%s | load=%dms | n_threads=%d | n_threads_batch=%d | n_batch=%d | n_ubatch=%d | prompt=%d/%s ms | TTFT=%s ms | decode=%dms | output=%d | %.3f tok/s | finish=%s | warmup=%.3f tok/s",
                        profile.label, backend, loadMs, cfg.threads, cfg.batchThreads,
                        cfg.batchSize, cfg.microBatchSize, measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenMs?.toString() ?: "n/a",
                        measured.result.durationMs, measured.result.tokensGenerated,
                        measured.result.tokensPerSecond, measured.result.finishReason.name,
                        warmup.result.tokensPerSecond
                    )
                    rows += report
                    EngineDiagnostics.info(
                        "config_test",
                        "CONFIG_SMOKE_RESULT",
                        "profile=" + profile.id + ",backend=" + backend + ",loadMs=" + loadMs +
                            ",nThreads=" + cfg.threads + ",nThreadsBatch=" + cfg.batchThreads +
                            ",nBatch=" + cfg.batchSize + ",nUbatch=" + cfg.microBatchSize +
                            ",promptTokens=" + measured.promptTokens +
                            ",promptElapsedMs=" + (measured.promptEvaluationMs ?: -1L) +
                            ",ttftMs=" + (measured.firstTokenMs ?: -1L) +
                            ",decodeMs=" + measured.result.durationMs +
                            ",outputTokens=" + measured.result.tokensGenerated +
                            ",tokensPerSecond=" + measured.result.tokensPerSecond +
                            ",finishReason=" + measured.result.finishReason.name +
                            ",warmupTokensPerSecond=" + warmup.result.tokensPerSecond
                    )
                    if (measured.result.tokensGenerated < 4) {
                        throw IllegalStateException("Zu wenige Mess-Tokens im Profil " + profile.id)
                    }
                    if (index < profiles.lastIndex) delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                stoppedReason = "Vom Nutzer abgebrochen."
                rows += "ABGEBROCHEN: Der laufende Test wurde beendet."
                EngineDiagnostics.info("config_test", "CONFIG_SMOKE_CANCELLED")
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: " + stoppedReason
                EngineDiagnostics.error("config_test", "CONFIG_SMOKE_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _configSmokeReport.value = "Stelle die ursprüngliche Modellkonfiguration wieder her…"
                    withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                            .onSuccess { restored ->
                                EngineDiagnostics.info(
                                    "config_test",
                                    "CONFIG_SMOKE_RESTORED",
                                    "backendPreference=" + originalConfig.backendPreference +
                                        ",activeBackend=" + restored.activeBackend
                                )
                            }
                            .onFailure { error ->
                                rows += "KRITISCH: Restore fehlgeschlagen: " + (error.message ?: error.javaClass.simpleName)
                                EngineDiagnostics.error("config_test", "CONFIG_SMOKE_RESTORE_FAILED", error)
                            }
                    }
                }
                _configSmokeReport.value = buildString {
                    appendLine("Automatischer In-App-Konfigurationstest")
                    appendLine("Modell: " + model.name + " (" + model.sizeMb.toInt() + " MB)")
                    appendLine("Backend: " + (confirmedBackend ?: "nicht bestätigt"))
                    appendLine("Kontext: " + originalConfig.contextSize + "; GPU-Layer: " + originalConfig.gpuLayers)
                    appendLine("4 Warmup-Tokens + 16 Mess-Tokens; Temperatur 0; Top-P 1.")
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: " + stoppedReason)
                    append("Restore-Status steht im Diagnoseexport.")
                }
                _isConfigSmokeTesting.value = false
                _isBenchmarking.value = false
                configurationSmokeJob = null
            }
        }
    }

    fun cancelConfigurationSmokeTest() {
        if (!_isConfigSmokeTesting.value) return
        EngineDiagnostics.info("config_test", "CONFIG_SMOKE_CANCEL_REQUESTED")
        engine.cancelInference()
        configurationSmokeJob?.cancel(CancellationException("Configuration smoke test cancelled"))
        _configSmokeReport.value = "Abbruch angefordert – sichere Wiederherstellung wird ausgeführt…"
    }

    private data class ConfigurationSmokeSample(
        val result: com.mydocanizer.llm.engine.InferenceResult,
        val firstTokenMs: Long?,
        val promptEvaluationMs: Long?,
        val promptTokens: Int
    )

    private suspend fun collectConfigurationSmokeSample(maxTokens: Int): ConfigurationSmokeSample {
        val started = System.nanoTime()
        var firstTokenMs: Long? = null
        var promptElapsedMs: Long? = null
        var promptTokens = 0
        var completed: com.mydocanizer.llm.engine.InferenceResult? = null
        val sequence = (1..160).joinToString(", ") { (it * 2).toString() }
        val prompt = "Analysiere diese Zahlenfolge und gib die nächsten fünf geraden Zahlen aus. " +
            "Antworte ausschließlich im Format ZAHL, ZAHL, ZAHL, ZAHL, ZAHL. Zahlenfolge: " + sequence

        engine.streamChat(
            InferenceRequest(
                messages = listOf(ChatMessage.user(prompt)),
                maxPredictTokens = maxTokens,
                temperature = 0f,
                topP = 1f
            )
        ).collect { event ->
            currentCoroutineContext().ensureActive()
            when (event) {
                is InferenceEvent.Progress -> if (event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION) {
                    promptElapsedMs = event.elapsedMs
                    promptTokens = event.promptTokensTotal
                }
                is InferenceEvent.Token -> if (firstTokenMs == null) {
                    firstTokenMs = (System.nanoTime() - started) / 1_000_000L
                }
                is InferenceEvent.Completed -> completed = event.result
                is InferenceEvent.Error -> throw event.throwable
                is InferenceEvent.Cancelled -> throw CancellationException("Native inference cancelled")
                is InferenceEvent.Started -> Unit
            }
        }
        val result = completed ?: throw IllegalStateException("Keine abgeschlossene Testinferenz erhalten.")
        if (result.text.isBlank()) throw IllegalStateException("Leere Testantwort.")
        if (!result.text.contains("322")) {
            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_CHECK_FAILED", "expectedFirstValue=322")
        }
        return ConfigurationSmokeSample(result, firstTokenMs, promptElapsedMs, promptTokens)
    }

    private fun configurationTestSafetyBlock(): String? {
        val app = getApplication<Application>()
        val manager = app.getSystemService(ActivityManager::class.java)
        if (manager != null) {
            val memory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            val minFree = 512L * 1024L * 1024L
            if (memory.lowMemory || memory.availMem < minFree) {
                return "Freier Systemspeicher nur " + (memory.availMem / (1024L * 1024L)) + " MiB; mindestens 512 MiB erforderlich."
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = app.getSystemService(PowerManager::class.java)
            val thermal = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return "Thermischer Status ist SEVERE oder höher."
        }
        return null
    }

    private fun backendFamily(value: String): String = when {
        value.contains("Vulkan", ignoreCase = true) -> "VULKAN"
        value.contains("CPU", ignoreCase = true) -> "CPU"
        else -> "UNKNOWN"
    }

    fun restartConversation() {
        // Invalidate queued events before cancelling the coroutine. A late native
        // completion from the old conversation must never repopulate the reset chat.
        generationSequence++
        engine.cancelInference()
        inferenceJob?.cancel()
        inferenceJob = null

        _chatMessages.value = emptyList()
        _currentStreamingText.value = ""
        _inferenceProgress.value = null
        _inputText.value = ""
        _isGenerating.value = false
        _statusMessage.value = "Gespräch wurde zurückgesetzt"
    }

    fun sendMessage() {
        if (_isBenchmarking.value) {
            _errorMessage.value = "Bitte den laufenden Benchmark erst abschließen."
            return
        }
        val prompt = _inputText.value.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (currentModel.value == null) {
            _errorMessage.value = "Kein Modell geladen! Bitte wähle oder importiere zuerst eine GGUF-Modelldatei."
            return
        }

        val userMsg = ChatMessage.user(prompt)
        val currentList = _chatMessages.value.toMutableList().apply { add(userMsg) }
        _chatMessages.value = currentList
        _inputText.value = ""
        _isGenerating.value = true
        _currentStreamingText.value = ""
        _inferenceProgress.value = null
        _errorMessage.value = null

        val messagesWithSystem = mutableListOf<ChatMessage>()
        if (_systemPrompt.value.isNotBlank()) {
            messagesWithSystem.add(ChatMessage.system(_systemPrompt.value))
        }
        messagesWithSystem.addAll(currentList)

        val request = InferenceRequest(
            messages = messagesWithSystem,
            maxPredictTokens = _engineConfig.value.maxPredictTokens,
            temperature = _engineConfig.value.temperature,
            topP = _engineConfig.value.topP
        )

        val thisGeneration = ++generationSequence
        inferenceJob = viewModelScope.launch {
            val streamBuilder = StringBuilder()
            try {
                engine.streamChat(request).collect { event ->
                    if (thisGeneration != generationSequence) return@collect
                    when (event) {
                    is InferenceEvent.Started -> {
                        Log.d(TAG, "Inference started with ${event.promptMessageCount} messages")
                    }
                    is InferenceEvent.Token -> {
                        streamBuilder.append(event.token)
                        _currentStreamingText.value = streamBuilder.toString()
                    }
                    is InferenceEvent.Progress -> {
                        _inferenceProgress.value = event
                    }
                    is InferenceEvent.Completed -> {
                        val assistantMsg = ChatMessage.assistant(event.result.text)
                        _chatMessages.value = _chatMessages.value + assistantMsg
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                        Log.i(TAG, "Completed: ${event.result.tokensGenerated} tokens @ ${event.result.tokensPerSecond} t/s")
                    }
                    is InferenceEvent.Cancelled -> {
                        val partialText = event.partialText.ifBlank { streamBuilder.toString() }
                        val partial = if (partialText.isNotBlank()) {
                            partialText + " [Abgebrochen]"
                        } else {
                            "[Generierung abgebrochen]"
                        }
                        _chatMessages.value = _chatMessages.value + ChatMessage.assistant(partial)
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                    }
                    is InferenceEvent.Error -> {
                        // Keep generated text in the conversation; the streaming bubble is transient.
                        val partialText = event.partialText.ifBlank { streamBuilder.toString() }
                        if (partialText.isNotBlank()) {
                            _chatMessages.value = _chatMessages.value + ChatMessage.assistant(
                                "$partialText\n\n[Teilantwort – Inferenzfehler]"
                            )
                        }
                        val reason = event.throwable.message ?: event.throwable.javaClass.simpleName
                        EngineDiagnostics.error("chat", "VIEWMODEL_RECEIVED_INFERENCE_ERROR", event.throwable)
                        _errorMessage.value = "Inferenz-Fehler: $reason"
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                    }
                }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (thisGeneration == generationSequence) {
                    Log.e(TAG, "Chat inference failed", error)
                    _errorMessage.value = "Inferenz-Fehler: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                if (thisGeneration == generationSequence) {
                    _isGenerating.value = false
                    _currentStreamingText.value = ""
                    _inferenceProgress.value = null
                    inferenceJob = null
                }
            }
        }
    }

    fun cancelGeneration() {
        // Keep the UI in its generating state until the native engine confirms cancellation.
        engine.cancelInference()
        _statusMessage.value = "Abbruch angefordert..."
    }

    fun exportDiagnostics(destination: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                EngineDiagnostics.exportTo(getApplication<Application>(), destination)
            }
            result.onSuccess { byteCount ->
                _statusMessage.value = "Diagnoseprotokoll exportiert ($byteCount Bytes)"
                _errorMessage.value = null
            }.onFailure { error ->
                EngineDiagnostics.error("diagnostics", "EXPORT_FAILED", error)
                _errorMessage.value = "Diagnoseprotokoll konnte nicht exportiert werden: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
