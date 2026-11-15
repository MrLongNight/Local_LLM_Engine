package com.example.ui.diagnostics

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig

/**
 * One short, model-agnostic Vulkan diagnostic run. No parameter matrix or
 * repeated model reloads: preserve the user's context and sampling settings,
 * request full GPU offload, and make output deterministic.
 */
internal object InferenceConfigSmokePlan {
    const val WARMUP_TOKENS = 4
    const val MEASURED_TOKENS = 16
    const val COOLDOWN_MS = 0L

    internal data class Profile(
        val id: String,
        val label: String,
        val config: EngineConfig
    )

    fun create(
        base: EngineConfig,
        availableProcessors: Int,
        activeBackend: String? = null
    ): List<Profile> {
        val config = base.copy(
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 99,
            temperature = 0f,
            topP = 1f,
            maxPredictTokens = WARMUP_TOKENS + MEASURED_TOKENS
        )
        return listOf(Profile("vulkan-single-run", "Vulkan · vollständiger GPU-Offload", config))
    }

    internal fun resolvedUbatchForTest(config: EngineConfig, activeBackend: String? = null): Int =
        if (config.microBatchSize > 0) minOf(config.microBatchSize, config.batchSize)
        else if (activeBackend?.contains("Vulkan", ignoreCase = true) == true ||
            (activeBackend == null && config.backendPreference != BackendPreference.CPU)
        ) minOf(64, config.batchSize) else config.batchSize
}
