package com.example.ui

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import kotlin.math.roundToInt

/**
 * Produces a small Vulkan layer-offload sweep based on the model's native-reported
 * total layer count. The ViewModel adds a second full-offload run as a drift check.
 */
internal object VulkanLayerSweepPlan {
    const val MAX_REQUESTED_GPU_LAYERS = 99
    private val fractionsPercent = listOf(100, 75, 50, 25)
    private val layerRegex = Regex("""requested layers\s+\d+/(\d+)""")

    fun layerCounts(totalLayers: Int): List<Int> {
        require(totalLayers > 0) { "totalLayers must be positive" }
        return fractionsPercent
            .map { fraction -> (totalLayers * fraction / 100.0).roundToInt().coerceIn(1, totalLayers) }
            .distinct()
    }

    fun totalLayersFromBackend(activeBackend: String): Int? =
        layerRegex.find(activeBackend)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun configuration(original: EngineConfig, gpuLayers: Int): EngineConfig =
        BackendBenchmarkPlan.configuration(original).copy(
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = gpuLayers.coerceIn(1, MAX_REQUESTED_GPU_LAYERS)
        )
}
