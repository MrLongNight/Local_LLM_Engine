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
 * Shared benchmark lock/report and common safety/backend helpers.
 */
abstract class BenchmarkDiagnosticsViewModel(
    application: Application,
    engine: LocalLlmEngine
) : SettingsViewModel(application, engine) {

    protected override val _isBenchmarking = MutableStateFlow(false)
    override val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    protected val _benchmarkReport = MutableStateFlow<String?>(null)
    val benchmarkReport: StateFlow<String?> = _benchmarkReport.asStateFlow()

    protected fun configurationTestSafetyBlock(): String? {
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

    protected fun backendFamily(value: String): String = when {
        value.contains("Vulkan", ignoreCase = true) -> "VULKAN"
        value.contains("CPU", ignoreCase = true) -> "CPU"
        else -> "UNKNOWN"
    }
}
