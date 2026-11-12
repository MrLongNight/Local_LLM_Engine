package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup

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

    data class FullMatrixProfile(
        val id: String,
        val backend: BackendPreference,
        val decodePath: VulkanMatvecWorkgroup,
        val microBatchSize: Int,
        val flashAttention: FlashAttentionMode,
        val config: EngineConfig
    )

    /**
     * Executable G1 cross-product: backend × decode path × n_ubatch × Flash Attention.
     * Vulkan includes AUTO and Q4_K base-DMMV; CPU has one decode path because the
     * Vulkan workgroup override is irrelevant there. The Q4_K-specific override is
     * measurement-only and must not become a default without target-device results.
     */
    fun fullMatrixProfiles(original: EngineConfig): List<FullMatrixProfile> {
        val profiles = mutableListOf<FullMatrixProfile>()
        for (backend in BACKENDS) {
            val decodePaths = if (backend == BackendPreference.VULKAN) {
                listOf(VulkanMatvecWorkgroup.AUTO, VulkanMatvecWorkgroup.Q4K_BASE_DMMV)
            } else {
                listOf(VulkanMatvecWorkgroup.AUTO)
            }
            for (decodePath in decodePaths) {
                for (microBatch in listOf(32, 64, 128)) {
                    for (flashAttention in listOf(FlashAttentionMode.DISABLED, FlashAttentionMode.ENABLED)) {
                        val isVulkan = backend == BackendPreference.VULKAN
                        val config = configuration(original).copy(
                            backendPreference = backend,
                            gpuLayers = if (isVulkan) 99 else 0,
                            threads = if (isVulkan) original.threads.coerceIn(1, 2) else original.threads,
                            batchThreads = if (isVulkan) {
                                original.batchThreads.takeIf { it > 0 }?.coerceIn(1, 2) ?: 0
                            } else original.batchThreads,
                            batchSize = maxOf(original.batchSize, microBatch),
                            microBatchSize = microBatch,
                            vulkanMatvecWorkgroup = decodePath,
                            flashAttentionMode = flashAttention
                        )
                        val id = "${backend.name}_${decodePath.name}_UB${microBatch}_FA_${flashAttention.name}"
                        profiles += FullMatrixProfile(
                            id = id,
                            backend = backend,
                            decodePath = decodePath,
                            microBatchSize = microBatch,
                            flashAttention = flashAttention,
                            config = config
                        )
                    }
                }
            }
        }
        return profiles
    }

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

    /** Q5_K/Q6_K base-MMV A/B paths; AUTO remains unchanged until measurements justify it. */
    fun vulkanQ5Q6DecodeConfigurations(original: EngineConfig): List<EngineConfig> {
        val paths = listOf(
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q5K_BASE_DMMV,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q6K_BASE_DMMV
        )
        val base = configuration(original).copy(backendPreference = BackendPreference.VULKAN, gpuLayers = 99)
        return paths.map { base.copy(vulkanMatvecWorkgroup = it) }
    }

    /** Q4_K prompt matmul conversion A/B sweep: upstream AUTO versus explicit F16/F32. */
    fun vulkanQ4kPrefillConfigurations(original: EngineConfig): List<EngineConfig> {
        val paths = listOf(
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_PREFILL_F16,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_PREFILL_F16,
            com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO
        )
        val base = configuration(original).copy(backendPreference = BackendPreference.VULKAN, gpuLayers = 99)
        return paths.map { base.copy(vulkanMatvecWorkgroup = it) }
    }

    /** Vulkan n_ubatch sweep for prompt throughput and cancellation granularity. */
    fun vulkanMicroBatchConfigurations(original: EngineConfig): List<EngineConfig> {
        val base = configuration(original).copy(backendPreference = BackendPreference.VULKAN, gpuLayers = 99)
        return listOf(32, 64, 128).map { size ->
            base.copy(batchSize = maxOf(base.batchSize, size), microBatchSize = size)
        }
    }

    /** Explicit Flash Attention ON/OFF profiles for each execution backend. */
    fun flashAttentionConfigurations(original: EngineConfig): List<EngineConfig> {
        val modes = listOf(FlashAttentionMode.DISABLED, FlashAttentionMode.ENABLED,
            FlashAttentionMode.ENABLED, FlashAttentionMode.DISABLED)
        val base = configuration(original)
        return listOf(BackendPreference.VULKAN, BackendPreference.CPU).flatMap { backend ->
            modes.map { mode -> base.copy(
                backendPreference = backend,
                gpuLayers = if (backend == BackendPreference.VULKAN) 99 else 0,
                flashAttentionMode = mode
            ) }
        }
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
