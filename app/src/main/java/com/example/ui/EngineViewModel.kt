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
            backendPreference = BackendPreference.CPU,
            gpuLayers = 16
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
        _systemPrompt.value = prompt
    }

    fun updateConfig(config: EngineConfig) {
        _engineConfig.value = config
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
        if (_isImporting.value) {
            EngineDiagnostics.info("ui", "MODEL_LOAD_REQUEST_IGNORED", "A model operation is already active")
            return
        }

        _isImporting.value = true
        _errorMessage.value = null
        _statusMessage.value = operationLabel

        viewModelScope.launch {
            try {
                val result = engine.loadModel(source, _engineConfig.value)
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
        startModelLoad(
            source = ModelSource.ContentUri(uriString = uri.toString()),
            operationLabel = "Importiere und lade GGUF-Modell über SAF..."
        )
    }

    fun loadModelFile(file: File) {
        startModelLoad(
            source = ModelSource.FilePath(file.absolutePath),
            operationLabel = "Lade Modell: ${file.name}..."
        )
    }

    fun unloadModel() {
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
