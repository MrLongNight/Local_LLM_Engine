package com.mydocanizer.llm.engine

import java.util.Locale

/**
 * Pure parsers and statistical helpers for structured inference/Vulkan metrics.
 * Kept separate from report composition so parsing rules can be tested independently.
 */
internal object EngineDiagnosticsMetrics {
    internal data class NativeTokenStepMetric(
        val pid: String,
        val runId: String,
        val backend: String,
        val index: Int,
        val sampleUs: Long,
        val decodeUs: Long,
        val decodeSubmitUs: Long,
        val backendSyncWaitUs: Long,
        val usedLegacyGpuSyncWaitLabel: Boolean,
        val pieceUs: Long,
        val totalUs: Long
    )

    internal data class KotlinTokenStepMetric(
        val pid: String,
        val runId: String,
        val backend: String,
        val index: Int,
        val jniWallUs: Long
    )

    internal data class VulkanOperationSample(
        val pid: String,
        val runId: String,
        val snapshot: String,
        val operation: String,
        val sampleCount: Int,
        val averageUs: Double,
        val totalUs: Double,
        val gflops: Double?
    )

    /**
     * Only metrics with an explicit runId are eligible for percentile aggregation.
     * Older files did not carry a request identity and may contain several backends
     * or inference runs; mixing those samples would produce misleading percentiles.
     */
    internal fun nativeTokenStepMetric(line: String): NativeTokenStepMetric? {
        if (!line.contains("TOKEN_STEP_METRIC")) return null
        val currentSyncField = EngineDiagnosticsReportBuilder.field(line, "backendSyncWaitUs")
        val legacyGpuSyncField = if (currentSyncField == null) EngineDiagnosticsReportBuilder.field(line, "gpuSyncWaitUs") else null
        return NativeTokenStepMetric(
            pid = EngineDiagnosticsReportBuilder.field(line, "pid") ?: return null,
            runId = EngineDiagnosticsReportBuilder.field(line, "runId") ?: return null,
            backend = EngineDiagnosticsReportBuilder.field(line, "backend") ?: return null,
            index = EngineDiagnosticsReportBuilder.field(line, "index")?.toIntOrNull() ?: return null,
            sampleUs = EngineDiagnosticsReportBuilder.field(line, "sampleUs")?.toLongOrNull() ?: return null,
            decodeUs = EngineDiagnosticsReportBuilder.field(line, "decodeUs")?.toLongOrNull() ?: return null,
            decodeSubmitUs = EngineDiagnosticsReportBuilder.field(line, "decodeSubmitUs")?.toLongOrNull() ?: return null,
            backendSyncWaitUs = (currentSyncField ?: legacyGpuSyncField)?.toLongOrNull() ?: return null,
            usedLegacyGpuSyncWaitLabel = currentSyncField == null && legacyGpuSyncField != null,
            pieceUs = EngineDiagnosticsReportBuilder.field(line, "pieceUs")?.toLongOrNull() ?: return null,
            totalUs = EngineDiagnosticsReportBuilder.field(line, "totalUs")?.toLongOrNull() ?: return null
        )
    }

    internal fun kotlinTokenStepMetric(line: String): KotlinTokenStepMetric? {
        if (!line.contains("TOKEN_STEP_METRIC") || line.contains("[NATIVE]")) return null
        return KotlinTokenStepMetric(
            pid = EngineDiagnosticsReportBuilder.field(line, "pid") ?: return null,
            runId = EngineDiagnosticsReportBuilder.field(line, "runId") ?: return null,
            backend = EngineDiagnosticsReportBuilder.field(line, "backend") ?: return null,
            index = EngineDiagnosticsReportBuilder.field(line, "nativeStep")?.toIntOrNull() ?: return null,
            jniWallUs = EngineDiagnosticsReportBuilder.field(line, "jniWallUs")?.toLongOrNull() ?: return null
        )
    }

    internal fun vulkanOperationSample(line: String): VulkanOperationSample? {
        if (!line.contains("LOCAL_LLM_VULKAN_OP_TIMING - ")) return null
        return VulkanOperationSample(
            pid = EngineDiagnosticsReportBuilder.field(line, "pid") ?: return null,
            runId = EngineDiagnosticsReportBuilder.field(line, "runId") ?: return null,
            snapshot = EngineDiagnosticsReportBuilder.field(line, "snapshot") ?: return null,
            operation = EngineDiagnosticsReportBuilder.field(line, "op") ?: return null,
            sampleCount = EngineDiagnosticsReportBuilder.field(line, "samples")?.toIntOrNull()?.takeIf { it > 0 } ?: return null,
            averageUs = EngineDiagnosticsReportBuilder.field(line, "avgUs")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null,
            totalUs = EngineDiagnosticsReportBuilder.field(line, "totalUs")?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: return null,
            gflops = EngineDiagnosticsReportBuilder.field(line, "gflops")?.toDoubleOrNull()?.takeIf { it >= 0.0 }
        )
    }

    internal fun doubleTimingSummary(name: String, values: List<Double>): String {
        if (values.isEmpty()) return "$name=n/a"
        val sorted = values.sorted()
        return "$name p50=${String.format(Locale.US, "%.3f", percentileDouble(sorted, 0.50))}us " +
            "p90=${String.format(Locale.US, "%.3f", percentileDouble(sorted, 0.90))}us " +
            "max=${String.format(Locale.US, "%.3f", sorted.last())}us"
    }

    internal fun percentile(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val boundedFraction = fraction.coerceIn(0.0, 1.0)
        val rank = sorted.lastIndex * boundedFraction
        val index = kotlin.math.ceil(rank).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    internal fun timingSummary(name: String, values: List<Long>): String {
        if (values.isEmpty()) return "${name}=n/a"
        return "${name} p50=${percentile(values, 0.50)}us p90=${percentile(values, 0.90)}us max=${values.maxOrNull()}us"
    }

    internal fun percentileDouble(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = sorted.lastIndex * fraction.coerceIn(0.0, 1.0)
        val lowerIndex = kotlin.math.floor(rank).toInt().coerceIn(0, sorted.lastIndex)
        val upperIndex = kotlin.math.ceil(rank).toInt().coerceIn(0, sorted.lastIndex)
        val fractionBetween = rank - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fractionBetween
    }
}
