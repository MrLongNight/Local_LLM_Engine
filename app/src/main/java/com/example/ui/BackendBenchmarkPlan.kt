package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig

/**
 * Single-request backend comparison: the initial generated tokens warm the execution
 * path, and the remaining tokens are timed without running a second expensive prefill.
 */
internal object BackendBenchmarkPlan {
    const val WARMUP_TOKENS = 4
    const val MEASURED_TOKENS = 20
    const val TOTAL_TOKENS = WARMUP_TOKENS + MEASURED_TOKENS
    const val COOLDOWN_MS = 500L

    val BACKENDS = listOf(BackendPreference.VULKAN, BackendPreference.CPU)

    fun configuration(original: EngineConfig): EngineConfig = original.copy(
        maxPredictTokens = TOTAL_TOKENS,
        temperature = 0f,
        topP = 1f
    )

    /**
     * Same deterministic benchmark settings and full Vulkan offload for every
     * profile. AUTO is repeated at the end to expose simple thermal/frequency drift.
     */
    fun vulkanMatvecConfigurations(original: EngineConfig): List<EngineConfig> {
        val workgroups = listOf(
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.SUBGROUP,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.LARGE,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO
        )
        val base = configuration(original).copy(
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 99
        )
        return workgroups.map { base.copy(vulkanMatvecWorkgroup = it) }
    }

    /**
     * Isolates Q4_K decode arithmetic: integer-dot MMVQ, base DMMV, base repeat, MMVQ repeat.
     * Only Q4_K decode changes; all other tensor types and inference settings remain fixed.
     */
    fun vulkanQ4kDecodeConfigurations(original: EngineConfig): List<EngineConfig> {
        val paths = listOf(
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO
        )
        val base = configuration(original).copy(
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 99
        )
        return paths.map { base.copy(vulkanMatvecWorkgroup = it) }
    }
}
