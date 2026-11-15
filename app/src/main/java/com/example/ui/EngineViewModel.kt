package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.LocalLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab {
    CHAT,
    IMPORT,
    SETTINGS
}

/**
 * Public app-level ViewModel facade. Public screen APIs and the one shared
 * ViewModel instance remain unchanged while feature clusters live in bases.
 */
class EngineViewModel(
    application: Application,
    engine: LocalLlmEngine
) : EngineLifecycleViewModel(application, engine) {

    constructor(application: Application) : this(
        application,
        LocalLlmEngine.create(application)
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = SettingsViewModel.DEFAULT_SYSTEM_PROMPT
    }

    private val _currentTab = MutableStateFlow(AppTab.CHAT)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
        EngineDiagnostics.info("ui", "TAB_SELECTED", "tab=$tab")
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
                val detail = error.message ?: error.javaClass.simpleName
                // statusMessage is rendered by MainScreen on every tab; don't leave
                // export failures hidden in a screen-specific error panel.
                _statusMessage.value = "Diagnose-Export fehlgeschlagen: $detail"
                _errorMessage.value = "Diagnoseprotokoll konnte nicht exportiert werden: $detail"
            }
        }
    }
}
