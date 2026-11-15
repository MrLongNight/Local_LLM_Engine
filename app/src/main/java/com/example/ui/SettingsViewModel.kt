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
 * Owns the shared model execution settings and system prompt used by chat,
 * model loading and benchmark flows. All screens still receive EngineViewModel.
 */
abstract class SettingsViewModel(
    application: Application,
    engine: LocalLlmEngine
) : ModelImportViewModel(application, engine) {

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Du bist ein hilfreicher, präziser KI-Assistent."
    }

    protected override val _engineConfig = MutableStateFlow(
        EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            // Vulkan offload is the default path; keep host-side Vulkan threads low.
            // CPU-only users can raise this explicitly in Settings.
            threads = 2,
            temperature = 0.7f,
            topP = 0.9f,
            maxPredictTokens = 512,
            backendPreference = BackendPreference.VULKAN,
            // Request full offload; llama.cpp caps this to the actual model layer count.
            gpuLayers = 99
        )
    )
    override val engineConfig: StateFlow<EngineConfig> = _engineConfig.asStateFlow()

    protected override val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    override val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

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
            previous.gdnPolicy != config.gdnPolicy ||
            previous.reloadAfterAbort != config.reloadAfterAbort ||
            previous.threads != config.threads ||
            previous.batchThreads != config.batchThreads ||
            previous.microBatchSize != config.microBatchSize ||
            previous.batchSize != config.batchSize ||
            previous.contextSize != config.contextSize ||
            previous.topK != config.topK ||
            previous.minP != config.minP
        ) {
            EngineDiagnostics.info(
                "ui",
                "EXECUTION_CONFIG_UPDATED",
                "backendPreference=${config.backendPreference}, gpuLayers=${config.gpuLayers}, " +
                    "vulkanMatvecWorkgroup=${config.vulkanMatvecWorkgroup}, " +
                    "flashAttentionMode=${config.flashAttentionMode}, gdnPolicy=${config.gdnPolicy}, " +
                    "reloadAfterAbort=${config.reloadAfterAbort}, " +
                    "threads=${config.threads}, batchThreads=${config.effectiveBatchThreads}, " +
                    "batch=${config.batchSize}, microBatchRequested=${config.microBatchSize}, " +
                    "context=${config.contextSize}, topK=${config.topK}, minP=${config.minP}"
            )
        }
    }
}
