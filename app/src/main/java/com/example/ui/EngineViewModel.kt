package com.example.ui

import android.app.Application
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var inferenceJob: Job? = null
    private var generationSequence: Long = 0L

    init {
        refreshModelFiles()
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
            previous.gpuLayers != config.gpuLayers
        ) {
            EngineDiagnostics.info(
                "ui",
                "EXECUTION_CONFIG_UPDATED",
                "backendPreference=${config.backendPreference}, gpuLayers=${config.gpuLayers}"
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
        // OpenDocument grants persistable read access so :inference can reopen
        // the same original document after a process restart.
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { error ->
            EngineDiagnostics.error("model_source", "SAF_PERSIST_PERMISSION_FAILED", error)
        }
        startModelLoad(
            source = ModelSource.ContentUri(uriString = uri.toString()),
            operationLabel = "Öffne GGUF-Datei direkt am Originalspeicherort..."
        )
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
     * Runs the fixed Ricardo Villalobos prompt on CPU and Vulkan using the current
     * app configuration. Only backendPreference changes between the two measured runs.
     * The model is reloaded for each backend and the user's original backend is restored.
     * Prompt/response text is never written to diagnostics.
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
        val benchmarkPrompt = "wer ist Ricardo Villalobos"
        val benchmarkConfig = originalConfig.copy()
        val backends = listOf(BackendPreference.CPU, BackendPreference.VULKAN)

        _isBenchmarking.value = true
        _benchmarkReport.value = "Benchmark wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val lines = mutableListOf<String>()
            EngineDiagnostics.info(
                "benchmark",
                "BACKEND_BENCHMARK_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},prompt=ricardo_villalobos_fixed," +
                    "context=${benchmarkConfig.contextSize},batch=${benchmarkConfig.batchSize}," +
                    "threads=${benchmarkConfig.threads},gpuLayers=${benchmarkConfig.gpuLayers}," +
                    "temperature=${benchmarkConfig.temperature},topP=${benchmarkConfig.topP}," +
                    "maxPredictTokens=${benchmarkConfig.maxPredictTokens},systemPromptChars=${systemPromptSnapshot.length}"
            )

            try {
                for (backend in backends) {
                    _benchmarkReport.value = "Lade ${backend.name} für den identischen Test…"
                    try {
                        val backendConfig = benchmarkConfig.copy(backendPreference = backend)
                        val loadStarted = System.nanoTime()
                        val loaded = engine.loadModel(source, backendConfig).getOrElse { throw it }
                        val modelLoadMs = (System.nanoTime() - loadStarted) / 1_000_000L

                        val messages = buildList {
                            if (systemPromptSnapshot.isNotBlank()) add(ChatMessage.system(systemPromptSnapshot))
                            add(ChatMessage.user(benchmarkPrompt))
                        }
                        val request = InferenceRequest(
                            messages = messages,
                            maxPredictTokens = backendConfig.maxPredictTokens,
                            temperature = backendConfig.temperature,
                            topP = backendConfig.topP
                        )

                        var firstTokenMs: Long? = null
                        var promptProgressElapsedMs: Long? = null
                        var promptTokensTotal = 0
                        var promptTokensProcessed = 0
                        var completed: com.mydocanizer.llm.engine.InferenceResult? = null
                        val inferenceStarted = System.nanoTime()

                        engine.streamChat(request).collect { event ->
                            when (event) {
                                is InferenceEvent.Progress -> {
                                    if (event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION) {
                                        promptProgressElapsedMs = event.elapsedMs
                                        promptTokensTotal = event.promptTokensTotal
                                        promptTokensProcessed = event.promptTokensProcessed
                                    }
                                }
                                is InferenceEvent.Token -> {
                                    if (firstTokenMs == null) {
                                        firstTokenMs = (System.nanoTime() - inferenceStarted) / 1_000_000L
                                    }
                                }
                                is InferenceEvent.Completed -> completed = event.result
                                is InferenceEvent.Error -> throw event.throwable
                                is InferenceEvent.Cancelled -> throw IllegalStateException(
                                    "Benchmark-Inferenz wurde unerwartet abgebrochen"
                                )
                                is InferenceEvent.Started -> Unit
                            }
                        }

                        val inferenceWallMs = (System.nanoTime() - inferenceStarted) / 1_000_000L
                        val result = completed
                            ?: throw IllegalStateException("Keine abgeschlossene Inferenzantwort erhalten")
                        val reportLine = String.format(
                            java.util.Locale.US,
                            "%s: backend=%s, modelLoadMs=%d, promptTokens=%d, prefillProgressMs=%s, " +
                                "timeToFirstTokenMs=%s, generationMs=%d, outputTokens=%d, tokensPerSecond=%.3f, " +
                                "streamWallMs=%d, finishReason=%s, activeBackend=%s",
                            backend.name,
                            backend.name,
                            modelLoadMs,
                            promptTokensTotal.coerceAtLeast(promptTokensProcessed),
                            promptProgressElapsedMs?.toString() ?: "n/a",
                            firstTokenMs?.toString() ?: "n/a",
                            result.durationMs,
                            result.tokensGenerated,
                            result.tokensPerSecond,
                            inferenceWallMs,
                            result.finishReason.name,
                            loaded.activeBackend
                        )
                        lines.add(reportLine)
                        EngineDiagnostics.info(
                            "benchmark",
                            "BACKEND_BENCHMARK_RESULT",
                            reportLine + ",context=${backendConfig.contextSize},batch=${backendConfig.batchSize}," +
                                "threads=${backendConfig.threads},gpuLayers=${backendConfig.gpuLayers}," +
                                "temperature=${backendConfig.temperature},topP=${backendConfig.topP}," +
                                "maxPredictTokens=${backendConfig.maxPredictTokens}"
                        )
                    } catch (error: Throwable) {
                        val failure = "${backend.name}: FEHLER - ${error.message ?: error.javaClass.simpleName}"
                        lines.add(failure)
                        EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_FAILED", error)
                    }
                }
            } finally {
                _benchmarkReport.value = "Stelle ursprüngliche Backend-Einstellung wieder her…"
                val restore = runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                restore.onSuccess { restored ->
                    EngineDiagnostics.info(
                        "benchmark",
                        "BACKEND_BENCHMARK_RESTORED",
                        "backendPreference=${originalConfig.backendPreference},activeBackend=${restored.activeBackend}"
                    )
                }.onFailure { error ->
                    lines.add("Wiederherstellung des ursprünglichen Backends fehlgeschlagen: ${error.message}")
                    EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_RESTORE_FAILED", error)
                }

                _benchmarkReport.value = buildString {
                    appendLine("Frage: wer ist Ricardo Villalobos")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine(
                        "Fixe Parameter: Kontext ${benchmarkConfig.contextSize}, Batch ${benchmarkConfig.batchSize}, " +
                            "${benchmarkConfig.threads} Threads, GPU-Layer ${benchmarkConfig.gpuLayers}, " +
                            "Temp ${benchmarkConfig.temperature}, Top-P ${benchmarkConfig.topP}, " +
                            "max. ${benchmarkConfig.maxPredictTokens} Tokens"
                    )
                    lines.forEach { appendLine(it) }
                    append("RAM-Messwerte stehen im Diagnose-Export unter RESOURCE_SNAPSHOT.")
                }
                _isBenchmarking.value = false
            }
        }
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
