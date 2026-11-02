package com.mydocanizer.llm.engine

/**
 * Detailed metadata of a loaded GGUF model.
 *
 * @property name Human-readable model or file name.
 * @property path Absolute file path where the model resides.
 * @property sizeBytes Size of the model file in bytes.
 * @property parameterCount Estimated number of parameters (e.g. 1.5B, 3B, 7B).
 * @property contextLengthTrain Native training context length of the model.
 * @property architecture Architecture identifier (e.g. llama, qwen2, phi3).
 * @property description Extra model description or quantization string.
 * @property activeBackend The currently active native execution backend (e.g. Vulkan GPU, CPU).
 */
data class ModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val parameterCount: Long = 0L,
    val contextLengthTrain: Int = 0,
    val architecture: String = "GGUF",
    val description: String = "",
    val activeBackend: String = "CPU",
    val sha256: String? = null
) {
    val sizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)

    val sizeGb: Double
        get() = sizeBytes / (1024.0 * 1024.0 * 1024.0)

    val formattedParamCount: String
        get() = when {
            parameterCount >= 1_000_000_000 -> String.format("%.2fB", parameterCount / 1e9)
            parameterCount >= 1_000_000 -> String.format("%.1fM", parameterCount / 1e6)
            parameterCount > 0 -> "$parameterCount"
            else -> "Unknown"
        }
}
