package com.example.ui

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
import com.mydocanizer.llm.engine.ModelSource
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
                if (documents.isEmpty()) {
                    _statusMessage.value = "Im ausgewählten Ordner wurden keine .gguf-Dateien gefunden."
                    return@launch
                }

                for ((index, document) in documents.withIndex()) {
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

                    if (!displayName.endsWith(".gguf", ignoreCase = true)) {
                        skipped++
                        failures += "$displayName: keine GGUF-Datei"
                        continue
                    }

                    try {
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
}
