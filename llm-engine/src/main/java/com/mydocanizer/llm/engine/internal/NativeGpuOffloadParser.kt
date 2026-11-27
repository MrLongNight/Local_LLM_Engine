package com.mydocanizer.llm.engine.internal

/** Actual layer allocation reported by llama.cpp during a single model-load attempt. */
internal data class NativeGpuOffload(
    val allocatedLayers: Int,
    val totalLayers: Int
)

/**
 * Parses llama.cpp's model-load summary, not backend availability or device registration.
 * The last valid record wins because model loading can emit intermediate summaries.
 */
internal object NativeGpuOffloadParser {
    private val offloadPattern = Regex(
        """(?:llama_model_load|load_tensors): *offloaded +([0-9]+)/([0-9]+) +layers +to GPU""",
        RegexOption.IGNORE_CASE
    )

    fun parse(log: String): NativeGpuOffload? {
        var latest: NativeGpuOffload? = null
        for (line in log.lineSequence()) {
            val match = offloadPattern.find(line) ?: continue
            val allocated = match.groupValues[1].toIntOrNull() ?: continue
            val total = match.groupValues[2].toIntOrNull() ?: continue
            if (total <= 0 || allocated < 0 || allocated > total) continue
            latest = NativeGpuOffload(allocated, total)
        }
        return latest
    }
}
