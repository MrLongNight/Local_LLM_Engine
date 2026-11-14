package com.example.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelLibrary
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImportedModelEntry(
    val file: File,
    val name: String,
    val sizeBytes: Long
) {
    val sizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)
}

abstract class ModelManagementViewModel(
    application: Application,
    engine: LocalLlmEngine
) : ChatViewModel(application, engine) {

    val engineState: StateFlow<EngineState> = engine.state
    override val currentModel: StateFlow<ModelInfo?> = engine.currentModel
    val backendInfo: StateFlow<BackendInfo> = engine.backendInfo
    val modelLoadProgress: StateFlow<Int?> = engine.modelLoadProgress

    private val _importedModelEntries = MutableStateFlow<List<ImportedModelEntry>>(emptyList())
    val importedModelEntries: StateFlow<List<ImportedModelEntry>> = _importedModelEntries.asStateFlow()

    private val _memoryOverrideAvailable = MutableStateFlow(false)
    val memoryOverrideAvailable: StateFlow<Boolean> = _memoryOverrideAvailable.asStateFlow()
    private var pendingMemoryOverride: Pair<ModelSource, EngineConfig>? = null

    protected val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val lastModelStore = LastModelStore(application)
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
                            name = ModelLibrary.displayName(file.name),
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
    protected fun startModelLoad(source: ModelSource, operationLabel: String, loadConfigOverride: EngineConfig? = null) {
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
        _memoryOverrideAvailable.value = false
        pendingMemoryOverride = null
        _statusMessage.value = operationLabel
        // Capture the exact settings at the user's load action. A later slider/chip
        // change must not silently alter a queued model-load operation.
        val loadConfig = loadConfigOverride ?: _engineConfig.value
        EngineDiagnostics.info(
            "ui",
            "MODEL_LOAD_CONFIGURATION",
            "operation=$operationLabel, backendPreference=${loadConfig.backendPreference}, " +
                "gpuLayers=${loadConfig.gpuLayers}, context=${loadConfig.contextSize}, " +
                "batch=${loadConfig.batchSize}, threads=${loadConfig.threads}, " +
                "batchThreads=${loadConfig.effectiveBatchThreads}, microBatchRequested=${loadConfig.microBatchSize}, " +
                "topK=${loadConfig.topK}, minP=${loadConfig.minP}"
        )

        viewModelScope.launch {
            try {
                val result = engine.loadModel(source, loadConfig)
                result.onSuccess { info ->
                    runCatching { lastModelStore.save(source, loadConfig.copy(memoryGuardOverride = false), info.name) }
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
                    if (requiresMemoryGuardOverride(error)) {
                        pendingMemoryOverride = source to loadConfig.copy(memoryGuardOverride = false)
                        _memoryOverrideAvailable.value = true
                    }
                    _errorMessage.value = "Modell konnte nicht geladen werden: ${error.message ?: error.javaClass.simpleName}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                EngineDiagnostics.error("ui", "MODEL_LOAD_UI_EXCEPTION", error)
                if (requiresMemoryGuardOverride(error)) {
                    pendingMemoryOverride = source to loadConfig.copy(memoryGuardOverride = false)
                    _memoryOverrideAvailable.value = true
                }
                _errorMessage.value = "Modell konnte nicht geladen werden: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun loadModelDespiteMemoryWarning() {
        if (_isImporting.value || _isBenchmarking.value) return
        val pending = pendingMemoryOverride ?: return
        pendingMemoryOverride = null
        _memoryOverrideAvailable.value = false
        startModelLoad(
            source = pending.first,
            operationLabel = "Modell wird trotz Speicherwarnung geladen…",
            loadConfigOverride = pending.second.copy(memoryGuardOverride = true)
        )
    }

    private fun requiresMemoryGuardOverride(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            if (current?.message?.startsWith("MEMORY_GUARD_OVERRIDE_REQUIRED:") == true) return true
            current = current?.cause
        }
        return false
    }
    /**
     * Restores the last successfully loaded model once when a fresh ViewModel starts.
     * SAF sources are reopened only when Android still holds the persisted read grant.
     */
    protected fun restoreLastModelOnStartup() {
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


}
