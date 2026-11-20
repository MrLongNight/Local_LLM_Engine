package com.example.ui.modelimport

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelLibrary
import android.provider.OpenableColumns
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

/**
 * Owns app-side SAF import entry points. Remote HTTPS materialization remains
 * in the llm-engine ModelSourceResolver; no duplicate download layer is added.
 */
abstract class ModelImportViewModel(
    application: Application,
    engine: LocalLlmEngine
) : ModelManagementViewModel(application, engine) {

    /**
     * Bulk-imports selected SAF documents into the private model library without
     * loading each model into RAM. Each document is handled independently so one
     * provider failure does not discard successful imports from the same selection.
     */
    /**
     * Imports every GGUF file below a user-selected SAF folder, including nested folders.
     * The complete folder scan happens before copying begins; each model is then verified
     * and published independently without loading it into inference memory.
     */
    fun importModelsFromTree(treeUri: Uri) {
        if (_isImporting.value || _isBenchmarking.value) return
        _isImporting.value = true
        _errorMessage.value = null
        _statusMessage.value = "Durchsuche Modellordner nach GGUF-Dateien…"

        viewModelScope.launch {
            var imported = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            try {
                currentCoroutineContext().ensureActive()
                val resolver = getApplication<Application>().contentResolver
                val hasGrant = resolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission
                }
                if (!hasGrant) {
                    resolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                val documents = SafGgufFolderScanner.scan(getApplication(), treeUri)
                currentCoroutineContext().ensureActive()
                if (documents.isEmpty()) {
                    _statusMessage.value = "Im ausgewählten Ordner wurden keine .gguf-Dateien gefunden."
                    return@launch
                }

                for ((index, document) in documents.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        ModelLibrary.importFromSaf(
                            getApplication(),
                            document.uri,
                            document.displayName
                        )
                        imported++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        skipped++
                        failures += "${document.displayName}: ${error.message ?: error.javaClass.simpleName}"
                        EngineDiagnostics.error("model_import", "FOLDER_IMPORT_FAILED", error)
                    }
                    _statusMessage.value =
                        "Ordnerimport: ${index + 1}/${documents.size} – ${document.displayName}"
                }

                refreshModelFiles()
                _statusMessage.value =
                    "Ordnerimport abgeschlossen: $imported importiert, $skipped fehlgeschlagen."
                if (failures.isNotEmpty()) {
                    _errorMessage.value = failures.take(5).joinToString("\\n")
                }
                EngineDiagnostics.info(
                    "model_import",
                    "FOLDER_IMPORT_COMPLETED",
                    "found=${documents.size}, imported=$imported, failed=$skipped"
                )
            } catch (cancelled: CancellationException) {
                _statusMessage.value = if (imported > 0) {
                    "Ordnerimport abgebrochen: $imported Datei(en) wurden bereits importiert."
                } else {
                    "Ordnerimport abgebrochen."
                }
                if (imported > 0) refreshModelFiles()
                EngineDiagnostics.info(
                    "model_import",
                    "FOLDER_IMPORT_CANCELLED",
                    "imported=$imported, skipped=$skipped"
                )
                throw cancelled
            } catch (error: Exception) {
                EngineDiagnostics.error("model_import", "FOLDER_SCAN_FAILED", error)
                _errorMessage.value =
                    "Modellordner konnte nicht importiert werden: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importModelsFromSaf(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value || _isBenchmarking.value) return
        _isImporting.value = true
        _errorMessage.value = null
        _statusMessage.value = "Importiere ${uris.size} GGUF-Datei(en)…"

        viewModelScope.launch {
            var imported = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val displayName = runCatching {
                        getApplication<Application>().contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (column >= 0) cursor.getString(column) else null
                            } else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Modell ${index + 1}.gguf"

                    currentCoroutineContext().ensureActive()

                    if (!displayName.endsWith(".gguf", ignoreCase = true)) {
                        skipped++
                        failures += "$displayName: keine GGUF-Datei"
                        continue
                    }

                    try {
                        currentCoroutineContext().ensureActive()
                        val resolver = getApplication<Application>().contentResolver
                        val hasGrant = resolver.persistedUriPermissions.any {
                            it.uri == uri && it.isReadPermission
                        }
                        if (!hasGrant) {
                            resolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.IO) {
                            ModelLibrary.importFromSaf(getApplication(), uri, displayName)
                        }
                        imported++
                        _statusMessage.value =
                            "Importiere Modelle: ${index + 1}/${uris.size} – $displayName"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        skipped++
                        failures += "$displayName: ${error.message ?: error.javaClass.simpleName}"
                        EngineDiagnostics.error("model_import", "BULK_IMPORT_FAILED", error)
                    }
                }
                refreshModelFiles()
                _statusMessage.value = "GGUF-Import abgeschlossen: $imported importiert, $skipped übersprungen/fehlgeschlagen."
                if (failures.isNotEmpty()) {
                    _errorMessage.value = failures.take(5).joinToString("\n")
                }
                EngineDiagnostics.info(
                    "model_import",
                    "BULK_IMPORT_COMPLETED",
                    "selected=${uris.size}, imported=$imported, skipped=$skipped"
                )
            } catch (cancelled: CancellationException) {
                _statusMessage.value = if (imported > 0) {
                    "GGUF-Import abgebrochen: $imported Datei(en) wurden bereits importiert."
                } else {
                    "GGUF-Import abgebrochen."
                }
                if (imported > 0) refreshModelFiles()
                EngineDiagnostics.info(
                    "model_import",
                    "BULK_IMPORT_CANCELLED",
                    "selected=${uris.size}, imported=$imported, skipped=$skipped"
                )
                throw cancelled
            } finally {
                _isImporting.value = false
            }
        }
    }

    /**
     * A single-file selection is the one-item form of the exact same import flow
     * used by multi-select. Both publish into the private, verified model library;
     * loading is then initiated explicitly from the imported-model list.
     */
    fun importModelFromSaf(uri: Uri) {
        importModelsFromSaf(listOf(uri))
    }
}