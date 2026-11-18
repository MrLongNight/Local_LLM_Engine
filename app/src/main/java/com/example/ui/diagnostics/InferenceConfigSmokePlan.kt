package com.example.ui.diagnostics

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig

/**
 * Fixed, short CPU/Vulkan comparison. Both profiles share model, context,
 * batch, threads and deterministic sampling; only backend-specific offload
 * settings differ. No parameter matrix or tuning sweep is performed.
 */
internal object InferenceConfigSmokePlan {
    const val MEASURED_TOKENS = 20
    const val COOLDOWN_MS = 0L

    internal data class Profile(
        val id: String,
        val label: String,
        val expectedBackend: String,
        val config: EngineConfig
    )

    fun create(
        base: EngineConfig,
        availableProcessors: Int,
        activeBackend: String? = null
    ): List<Profile> {
        val common = base.copy(
            temperature = 0f,
            topP = 1f,
            maxPredictTokens = MEASURED_TOKENS
        )
        return listOf(
            Profile(
                id = "cpu-reference",
                label = "CPU · Referenz",
                expectedBackend = "CPU",
                config = common.copy(
                    backendPreference = BackendPreference.CPU,
                    gpuLayers = 0
                )
            ),
            Profile(
                id = "vulkan-candidate",
                label = "Vulkan · Kandidat",
                expectedBackend = "VULKAN",
                config = common.copy(
                    backendPreference = BackendPreference.VULKAN,
                    gpuLayers = 99
                )
            )
        )
    }

    internal fun resolvedUbatchForTest(config: EngineConfig, activeBackend: String? = null): Int =
        if (config.microBatchSize > 0) minOf(config.microBatchSize, config.batchSize)
        else if (activeBackend?.contains("Vulkan", ignoreCase = true) == true ||
            (activeBackend == null && config.backendPreference != BackendPreference.CPU)
        ) minOf(64, config.batchSize) else config.batchSize
}
