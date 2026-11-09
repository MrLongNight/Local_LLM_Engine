package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig

/**
 * A short comparison is deliberate: the benchmark must always reach the CPU phase,
 * even when a slow GPU would make a full user-configured response take many minutes.
 */
internal object BackendBenchmarkPlan {
    const val WARMUP_TOKENS = 4
    const val MEASURED_TOKENS = 24
    const val COOLDOWN_MS = 500L

    val BACKENDS = listOf(BackendPreference.VULKAN, BackendPreference.CPU)

    fun configuration(original: EngineConfig): EngineConfig = original.copy(
        maxPredictTokens = MEASURED_TOKENS,
        temperature = 0f,
        topP = 1f
    )
}
