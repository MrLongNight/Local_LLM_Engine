package com.example.ui.diagnostics

internal enum class ConfigurationSmokeRunStatus {
    RUNNING,
    PASSED,
    FAILED,
    CANCELLED,
    REJECTED
}

internal enum class ConfigurationSmokePhase {
    PREPARING,
    CPU_MODEL_LOADING,
    CPU_INFERENCE,
    VULKAN_MODEL_LOADING,
    VULKAN_INFERENCE,
    CHECKING_RESULTS,
    RESTORING_MODEL,
    COMPLETE
}

/**
 * UI-safe status for the paired CPU/Vulkan smoke test.
 *
 * Loading phases are intentionally indeterminate: the current engine contract exposes
 * model-load lifecycle state, but no trustworthy load percentage. Prompt and generation
 * counters are shown only when the native stream actually reports them.
 */
internal data class ConfigurationSmokeProgress(
    val status: ConfigurationSmokeRunStatus,
    val phase: ConfigurationSmokePhase,
    val message: String,
    val currentStep: Int,
    val totalSteps: Int = 7,
    val profileLabel: String? = null,
    val promptTokensProcessed: Int? = null,
    val promptTokensTotal: Int? = null,
    val generatedTokens: Int? = null,
    val tokenLimit: Int? = null,
    val tokensPerSecond: Double? = null
) {
    val isRunning: Boolean
        get() = status == ConfigurationSmokeRunStatus.RUNNING

    val stepProgress: Float
        get() {
            val safeTotal = totalSteps.coerceAtLeast(1)
            return currentStep.coerceIn(0, safeTotal).toFloat() / safeTotal.toFloat()
        }

    val promptProgress: Float?
        get() {
            val total = promptTokensTotal ?: return null
            val processed = promptTokensProcessed ?: return null
            if (total <= 0) return null
            return processed.coerceIn(0, total).toFloat() / total.toFloat()
        }

    val tokenProgress: Float?
        get() {
            val limit = tokenLimit ?: return null
            val generated = generatedTokens ?: return null
            if (limit <= 0) return null
            return generated.coerceIn(0, limit).toFloat() / limit.toFloat()
        }
}
