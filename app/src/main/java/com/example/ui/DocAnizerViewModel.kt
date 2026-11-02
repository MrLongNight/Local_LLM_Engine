package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

enum class AppTab {
    CHAT,
    IMPORT,
    SETTINGS
}

data class ImportedModelEntry(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sha256: String? = null
) {
    val sizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)
}

class DocAnizerViewModel(
    application: Application,
    val engine: LocalLlmEngine
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        LocalLlmEngine.create(application)
    )

    companion object {
        private const val TAG = "DocAnizerViewModel"
        const val DEFAULT_SYSTEM_PROMPT =
            "Du bist ein hilfsbereiter, präziser KI-Assistent für das Dokumentenmanagement und die Textanalyse."
    }

    private val _currentTab = MutableStateFlow(AppTab.CHAT)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    val engineState: StateFlow<EngineState> = engine.state
    val currentModel: StateFlow<ModelInfo?> = engine.currentModel
    val backendInfo: StateFlow<BackendInfo> = engine.backendInfo

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage.assistant(
                "Willkommen bei Local LLM Engine! Wähle oder importiere ein GGUF-Modell, um lokal und datenschutzkonform zu chatten."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentStreamingText = MutableStateFlow("")
    val currentStreamingText: StateFlow<String> = _currentStreamingText.asStateFlow()

    private val _engineConfig = MutableStateFlow(
        EngineConfig(
            contextSize = 4096,
            batchSize = 512,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
            temperature = 0.7f,
            topP = 0.9f,
            maxPredictTokens = 1024,
            backendPreference = BackendPreference.AUTO,
            gpuLayers = 99
        )
    )
    val engineConfig: StateFlow<EngineConfig> = _engineConfig.asStateFlow()

    private val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _importedModelEntries = MutableStateFlow<List<ImportedModelEntry>>(emptyList())
    val importedModelEntries: StateFlow<List<ImportedModelEntry>> = _importedModelEntries.asStateFlow()

    // Backwards-compatible files list
    private val _importedModelFiles = MutableStateFlow<List<File>>(emptyList())
    val importedModelFiles: StateFlow<List<File>> = _importedModelFiles.asStateFlow()

    private val _selectedModelFile = MutableStateFlow<File?>(null)
    val selectedModelFile: StateFlow<File?> = _selectedModelFile.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        refreshModelFiles()
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
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
                        f.isFile && (f.name.endsWith(".gguf", ignoreCase = true) || f.name.endsWith(".bin", ignoreCase = true))
                    }?.sortedByDescending { it.lastModified() } ?: emptyList()

                    val entries = files.map { file ->
                        ImportedModelEntry(
                            file = file,
                            name = file.name,
                            sizeBytes = file.length()
                        )
                    }
                    _importedModelFiles.value = files
                    _importedModelEntries.value = entries
                    if (_selectedModelFile.value == null && files.isNotEmpty()) {
                        _selectedModelFile.value = files.first()
                    }
                } else {
                    _importedModelFiles.value = emptyList()
                    _importedModelEntries.value = emptyList()
                }
            }
        }
    }

    fun selectModelFile(file: File) {
        _selectedModelFile.value = file
    }

    /**
     * Import a model using Android Storage Access Framework (SAF).
     * Calculates SHA-256, optionally copies into the private app storage folder without
     * altering the original file, and loads the model into the native engine.
     */
    fun importModelFromSaf(uri: Uri, copyToPrivate: Boolean = true) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusMessage.value = "Importiere Modelldatei über SAF..."
            _errorMessage.value = null

            runCatching {
                val context = getApplication<Application>()
                val cr = context.contentResolver
                val displayName = queryFileName(context, uri) ?: "imported_model.gguf"

                val finalFile: File
                var sha256Hex: String = ""

                if (copyToPrivate) {
                    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                    val destFile = File(modelsDir, displayName)

                    withContext(Dispatchers.IO) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        cr.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                val buffer = ByteArray(65536)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                }
                            }
                        } ?: throw IllegalStateException("Konnte Uri $uri nicht öffnen.")
                        sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    finalFile = destFile
                } else {
                    // Compute SHA256 directly from stream without copying
                    withContext(Dispatchers.IO) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        cr.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                digest.update(buffer, 0, read)
                            }
                        }
                        sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    // For native loading, local file path is required by llama.cpp
                    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                    finalFile = File(modelsDir, displayName)
                    if (!finalFile.exists()) {
                        withContext(Dispatchers.IO) {
                            cr.openInputStream(uri)?.use { input ->
                                FileOutputStream(finalFile).use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }

                refreshModelFiles()
                _selectedModelFile.value = finalFile

                // Load the model into the native engine
                val res = engine.loadModel(ModelSource.FilePath(finalFile.absolutePath), _engineConfig.value)
                if (res.isSuccess) {
                    val info = res.getOrThrow()
                    _statusMessage.value = "Modell '${info.name}' erfolgreich geladen (SHA-256: ${sha256Hex.take(12)}...)"
                    _errorMessage.value = null
                } else {
                    val cause = res.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                    _errorMessage.value = "Modell konnte nicht geladen werden: $cause"
                }
            }.onFailure { e ->
                Log.e(TAG, "Fehler beim Modellimport", e)
                _errorMessage.value = "Import fehlgeschlagen: ${e.message}"
            }

            _isImporting.value = false
        }
    }

    fun loadSelectedModel() {
        val file = _selectedModelFile.value ?: return
        loadExistingModel(file)
    }

    fun loadExistingModel(file: File) {
        viewModelScope.launch {
            _statusMessage.value = "Lade Modell: ${file.name}..."
            _errorMessage.value = null
            _selectedModelFile.value = file

            val res = engine.loadModel(ModelSource.FilePath(file.absolutePath), _engineConfig.value)
            if (res.isSuccess) {
                val info = res.getOrThrow()
                _statusMessage.value = "Modell '${info.name}' bereit (${info.activeBackend})"
                _errorMessage.value = null
            } else {
                val msg = res.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                _errorMessage.value = "Fehler beim Laden von ${file.name}: $msg"
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unloadModel()
            _statusMessage.value = "Modell entladen"
            _errorMessage.value = null
        }
    }

    fun restartConversation() {
        _chatMessages.value = listOf(
            ChatMessage.assistant("Gespräch neu gestartet. Wie kann ich dir behilflich sein?")
        )
        _currentStreamingText.value = ""
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
        _errorMessage.value = null

        val messagesWithSystem = mutableListOf<ChatMessage>()
        if (_systemPrompt.value.isNotBlank()) {
            messagesWithSystem.add(ChatMessage.system(_systemPrompt.value))
        }
        messagesWithSystem.addAll(currentList)

        val request = InferenceRequest(
            messages = messagesWithSystem,
            maxPredictTokens = _engineConfig.value.maxPredictTokens,
            temperature = _engineConfig.value.temperature
        )

        viewModelScope.launch {
            val streamBuilder = StringBuilder()
            engine.streamChat(request).collect { event ->
                when (event) {
                    is InferenceEvent.Started -> {
                        Log.d(TAG, "Inference started with ${event.promptMessageCount} messages")
                    }
                    is InferenceEvent.Token -> {
                        streamBuilder.append(event.token)
                        _currentStreamingText.value = streamBuilder.toString()
                    }
                    is InferenceEvent.Completed -> {
                        val assistantMsg = ChatMessage.assistant(event.result.text)
                        _chatMessages.value = _chatMessages.value + assistantMsg
                        _currentStreamingText.value = ""
                        _isGenerating.value = false
                        Log.i(TAG, "Completed: ${event.result.tokensGenerated} tokens @ ${event.result.tokensPerSecond} t/s")
                    }
                    is InferenceEvent.Cancelled -> {
                        val partial = if (event.partialText.isNotBlank()) {
                            event.partialText + " [Abgebrochen]"
                        } else {
                            "[Generierung abgebrochen]"
                        }
                        _chatMessages.value = _chatMessages.value + ChatMessage.assistant(partial)
                        _currentStreamingText.value = ""
                        _isGenerating.value = false
                    }
                    is InferenceEvent.Error -> {
                        _errorMessage.value = "Inferenz-Fehler: ${event.throwable.message}"
                        _isGenerating.value = false
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        engine.cancelInference()
        _isGenerating.value = false
        _statusMessage.value = "Generierung abgebrochen"
    }

    fun clearChat() {
        restartConversation()
    }

    private fun queryFileName(context: android.content.Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
