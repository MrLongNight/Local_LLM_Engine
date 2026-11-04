package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

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
    val timeoutConfig: TimeoutConfig = TimeoutConfig()
) : Parcelable {
    init {
        require(contextSize >= 256) { "contextSize must be at least 256" }
        require(batchSize >= 64) { "batchSize must be at least 64" }
        require(threads >= 1) { "threads must be at least 1" }
        require(temperature >= 0f) { "temperature must be non-negative" }
        require(maxPredictTokens >= 1) { "maxPredictTokens must be at least 1" }
    }

    constructor(parcel: Parcel) : this(
        contextSize = parcel.readInt(),
        batchSize = parcel.readInt(),
        threads = parcel.readInt(),
        temperature = parcel.readFloat(),
        topP = parcel.readFloat(),
        maxPredictTokens = parcel.readInt(),
        backendPreference = BackendPreference.valueOf(parcel.readString() ?: BackendPreference.AUTO.name),
        gpuLayers = parcel.readInt(),
        timeoutConfig = parcel.readParcelable(TimeoutConfig::class.java.classLoader) ?: TimeoutConfig()
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
