package com.example.ui

import com.mydocanizer.llm.engine.EngineConfig

/**
 * Small on-device screening plan. Each candidate changes exactly one llama.cpp
 * context parameter. Context settings are applied at model-load time.
 */
internal object InferenceConfigSmokePlan {
    const val WARMUP_TOKENS = 4
    const val MEASURED_TOKENS = 16
    const val COOLDOWN_MS = 1_500L

    internal data class Profile(
        val id: String,
        val label: String,
        val config: EngineConfig
    )

    fun create(base: EngineConfig, availableProcessors: Int): List<Profile> {
        val baseline = base.copy(
            batchThreads = if (base.batchThreads > 0) base.batchThreads else base.threads,
            microBatchSize = if (base.microBatchSize > 0) base.microBatchSize else base.batchSize,
            temperature = 0f,
            topP = 1f,
            maxPredictTokens = MEASURED_TOKENS
        )
        val deviceThreads = availableProcessors.coerceIn(2, 8)
        val smallerMicroBatch = minOf(32, baseline.batchSize)
        val candidates = listOf(
            Profile("threads-default", "n_threads=4 (Standardwert)", baseline.copy(threads = 4)),
            Profile("threads-device", "n_threads=$deviceThreads (Geräte-Kerne)", baseline.copy(threads = deviceThreads)),
            Profile("batch-threads-default", "n_threads_batch=4 (Standardwert)", baseline.copy(batchThreads = 4)),
            Profile("batch-default", "n_batch=512 (Standardwert)", baseline.copy(batchSize = 512)),
            Profile(
                "micro-batch-32",
                "n_ubatch=$smallerMicroBatch (kleiner Microbatch)",
                baseline.copy(microBatchSize = smallerMicroBatch)
            )
        )

        val result = mutableListOf(Profile("baseline", "Ausgangskonfiguration", baseline))
        val seen = mutableSetOf(signature(baseline))
        candidates.forEach { candidate ->
            if (seen.add(signature(candidate.config))) result += candidate
        }
        return result
    }

    private fun signature(config: EngineConfig): String =
        config.threads.toString() + ":" +
            config.batchThreads + ":" +
            config.batchSize + ":" +
            config.microBatchSize
}
