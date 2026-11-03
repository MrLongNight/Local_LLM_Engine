package com.mydocanizer.llm.engine

/**
 * Backend selection policy for native model offloading.
 */
enum class BackendPreference(val modeId: Int, val label: String) {
    /**
     * Uses a GPU backend only when llama.cpp has actually registered one;
     * otherwise falls back to CPU.
     */
    AUTO(0, "Automatisch (GPU-Backend falls verfügbar, sonst CPU)"),

    /**
     * Enforces llama.cpp's Vulkan backend.
     * Fails if the backend is not compiled, registered, or usable (no silent fallback).
     */
    VULKAN(1, "Vulkan GPU erzwingen (nur bei eingebautem Backend)"),

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
    val gpuLayers: Int = 99
) {
    init {
        require(contextSize >= 256) { "contextSize must be at least 256" }
        require(batchSize >= 64) { "batchSize must be at least 64" }
        require(threads >= 1) { "threads must be at least 1" }
        require(temperature.isFinite() && temperature >= 0f) {
            "temperature must be a finite, non-negative value"
        }
        require(topP.isFinite() && topP in 0f..1f) {
            "topP must be a finite value between 0 and 1"
        }
        require(maxPredictTokens >= 1) { "maxPredictTokens must be at least 1" }
        require(gpuLayers >= 1) { "gpuLayers must be at least 1; select CPU backend to disable offload" }
    }
}
