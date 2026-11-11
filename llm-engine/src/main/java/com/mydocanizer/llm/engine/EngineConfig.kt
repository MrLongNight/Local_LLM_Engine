package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

/**
 * Diagnostic workgroup selector for Vulkan dequantize-matvec kernels.
 * AUTO preserves the exact pinned llama.cpp vendor heuristic.
 */
enum class VulkanMatvecWorkgroup(val nativeId: Int, val label: String) {
    AUTO(0, "AUTO"),
    SUBGROUP(1, "SUBGROUP"),
    LARGE(2, "LARGE (4× Subgroup)"),
    // A/B-only base-type MMV selectors; AUTO applies the Mali Q4_K policy.
    Q4K_BASE_DMMV(3, "Q4_K Basis-DMMV (ohne Integer-Dot)"),
    Q5K_BASE_DMMV(4, "Q5_K Basis-DMMV (A/B)"),
    Q6K_BASE_DMMV(5, "Q6_K Basis-DMMV (A/B)"),
    Q4K_PREFILL_F16(6, "Q4_K Prefill F16/F32 (A/B)");

    companion object {
        fun fromNativeId(id: Int): VulkanMatvecWorkgroup =
            entries.firstOrNull { it.nativeId == id } ?: AUTO

        fun fromName(name: String?): VulkanMatvecWorkgroup =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Explicit Flash Attention policy passed to llama_context_params. */
enum class ReloadAfterAbort {
    /** Leave the model unloaded after process recovery; reload on the next inference request. */
    LAZY,
    /** Reload the remembered model immediately after the inference process reconnects. */
    EAGER
}

enum class FlashAttentionMode(val nativeId: Int) {
    AUTO(-1), DISABLED(0), ENABLED(1);

    companion object {
        fun fromNativeId(id: Int): FlashAttentionMode = entries.firstOrNull { it.nativeId == id } ?: AUTO
        fun fromName(name: String?): FlashAttentionMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Backend selection policy for native model offloading.
 */
enum class BackendPreference(val modeId: Int, val label: String) {
    /**
     * Tries Vulkan GPU acceleration first.
     * If Vulkan is unavailable on device, transparently falls back to CPU.
     */
    AUTO(0, "Automatisch (Vulkan GPU -> CPU)"),

    /**
     * Enforces Vulkan GPU execution.
     * Fails with a hard error if Vulkan cannot be initialized or no GPU is found (no silent fallback).
     */
    VULKAN(1, "Vulkan GPU erzwingen"),

    /**
     * Enforces CPU execution (ARM NEON / KleidiAI / AVX).
     */
    CPU(2, "CPU erzwingen");

    companion object {
        fun fromModeId(id: Int): BackendPreference =
            entries.firstOrNull { it.modeId == id } ?: AUTO
    }
}

/**
 * Runtime configuration parameters for model loading, backend offloading, and token generation.
 *
 * @property contextSize Maximum token context window (n_ctx). Clamped dynamically to model train limit.
 * @property batchSize Batch size for prompt evaluation (n_batch).
 * @property threads Number of CPU worker threads used for tensor operations.
 * @property temperature Sampling temperature (0.0 = greedy/deterministic, >0.0 = creative).
 * @property topP Nucleus sampling threshold.
 * @property maxPredictTokens Default token generation limit.
 * @property backendPreference Target execution backend (AUTO, VULKAN, CPU).
 * @property gpuLayers Number of layers offloaded to GPU VRAM (e.g. 99 for full offload).
 * @property batchThreads llama.cpp n_threads_batch; 0 follows n_threads.
 * @property microBatchSize llama.cpp n_ubatch; 0 follows n_batch.
 * @property vulkanMatvecWorkgroup Diagnostic DMMV workgroup selector; AUTO includes the Mali Q4_K policy.
 * @property flashAttentionMode Explicit Flash Attention selection.
 */
data class EngineConfig(
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxPredictTokens: Int = 1024,
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val gpuLayers: Int = 99,
    val timeoutConfig: TimeoutConfig = TimeoutConfig(),
    val batchThreads: Int = 0,
    val microBatchSize: Int = 0,
    val vulkanMatvecWorkgroup: VulkanMatvecWorkgroup = VulkanMatvecWorkgroup.AUTO,
    val flashAttentionMode: FlashAttentionMode = FlashAttentionMode.AUTO,
    val reloadAfterAbort: ReloadAfterAbort = ReloadAfterAbort.LAZY
) : Parcelable {
    /**
     * Effective llama.cpp thread count for prompt/batch processing.
     * A configured value of zero means "follow the main thread count".
     */
    val effectiveBatchThreads: Int
        get() = batchThreads.takeIf { it > 0 } ?: threads

    /**
     * Effective llama.cpp micro-batch size.
     * A configured value of zero means "follow the configured batch size".
     */
    val effectiveMicroBatchSize: Int
        get() = microBatchSize.takeIf { it > 0 } ?: batchSize

    init {
        require(contextSize >= 256) { "contextSize must be at least 256" }
        require(batchSize >= 64) { "batchSize must be at least 64" }
        require(threads >= 1) { "threads must be at least 1" }
        require(batchThreads >= 0) { "batchThreads must be zero (follow threads) or greater" }
        require(microBatchSize >= 0) { "microBatchSize must be zero (follow batchSize) or greater" }
        require(microBatchSize == 0 || microBatchSize <= batchSize) {
            "microBatchSize must not exceed batchSize"
        }
        require(temperature.isFinite() && temperature >= 0f) {
            "temperature must be finite and non-negative"
        }
        require(topP.isFinite() && topP in 0f..1f) {
            "topP must be finite and between 0 and 1"
        }
        require(maxPredictTokens >= 1) { "maxPredictTokens must be at least 1" }
    }

    constructor(parcel: Parcel) : this(
        contextSize = parcel.readInt(),
        batchSize = parcel.readInt(),
        threads = parcel.readInt(),
        temperature = parcel.readFloat(),
        topP = parcel.readFloat(),
        maxPredictTokens = parcel.readInt(),
        backendPreference = parcel.readString()?.let { name ->
            BackendPreference.entries.firstOrNull { it.name == name } ?: BackendPreference.AUTO
        } ?: BackendPreference.AUTO,
        gpuLayers = parcel.readInt(),
        timeoutConfig = if (android.os.Build.VERSION.SDK_INT >= 33) {
            parcel.readParcelable(TimeoutConfig::class.java.classLoader, TimeoutConfig::class.java) ?: TimeoutConfig()
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(TimeoutConfig::class.java.classLoader) ?: TimeoutConfig()
        },
        batchThreads = parcel.readInt(),
        microBatchSize = parcel.readInt(),
        vulkanMatvecWorkgroup = if (parcel.dataAvail() > 0) {
            VulkanMatvecWorkgroup.fromName(parcel.readString())
        } else VulkanMatvecWorkgroup.AUTO,
        flashAttentionMode = if (parcel.dataAvail() > 0) {
            FlashAttentionMode.fromName(parcel.readString())
        } else FlashAttentionMode.AUTO,
        reloadAfterAbort = if (parcel.dataAvail() > 0) {
            runCatching { ReloadAfterAbort.valueOf(parcel.readString().orEmpty()) }
                .getOrDefault(ReloadAfterAbort.LAZY)
        } else ReloadAfterAbort.LAZY
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(contextSize)
        parcel.writeInt(batchSize)
        parcel.writeInt(threads)
        parcel.writeFloat(temperature)
        parcel.writeFloat(topP)
        parcel.writeInt(maxPredictTokens)
        parcel.writeString(backendPreference.name)
        parcel.writeInt(gpuLayers)
        parcel.writeParcelable(timeoutConfig, flags)
        parcel.writeInt(batchThreads)
        parcel.writeInt(microBatchSize)
        parcel.writeString(vulkanMatvecWorkgroup.name)
        parcel.writeString(flashAttentionMode.name)
        parcel.writeString(reloadAfterAbort.name)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<EngineConfig> = object : Parcelable.Creator<EngineConfig> {
            override fun createFromParcel(parcel: Parcel): EngineConfig = EngineConfig(parcel)
            override fun newArray(size: Int): Array<EngineConfig?> = arrayOfNulls(size)
        }
    }
}
