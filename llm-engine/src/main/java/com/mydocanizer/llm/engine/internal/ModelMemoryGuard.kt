package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.BackendPreference

/**
 * Model-load guard that distinguishes the reclaimable GGUF page cache / shared UMA heap
 * from process-private allocations. The conservative total-RAM ceiling applies to every
 * backend. CPU loads can additionally require the estimated minimum footprint to fit in
 * currently available RAM; Vulkan retains the soft policy because its UMA/file-cache
 * accounting is not a reliable proxy for device-local allocations.
 */
internal object ModelMemoryGuard {
    const val MIN_CONTEXT_TOKENS = 256
    private const val MIN_KV_BYTES_PER_TOKEN = 128L * 1024L
    private const val MODEL_BYTES_PER_KV_ESTIMATE = 10_000L
    private const val RUNTIME_HEADROOM_BYTES = 256L * 1024L * 1024L
    private const val HARD_LIMIT_PERCENT = 85L
    private const val MIB = 1024L * 1024L
    const val OVERRIDE_REQUIRED_MARKER = "MEMORY_GUARD_OVERRIDE_REQUIRED:"
    const val CPU_MEMORY_SETTLING_SAMPLE_COUNT = 3
    const val CPU_MEMORY_SETTLING_MAX_VARIATION_BYTES = 128L * MIB
    const val CPU_MEMORY_SETTLING_MAX_WAIT_MS = 8_000L
    const val CPU_MEMORY_SETTLING_SAMPLE_INTERVAL_MS = 500L

    /**
     * A fresh available-RAM snapshot is not enough immediately after releasing a Vulkan
     * model on unified-memory devices. Require a short stabilization window only for the
     * Vulkan -> CPU transition; never let this policy override the explicit memory guard.
     */
    fun requiresPostVulkanCpuMemorySettling(
        previousBackend: String?,
        requestedBackend: BackendPreference,
        allowHardLimitOverride: Boolean
    ): Boolean =
        requestedBackend == BackendPreference.CPU &&
            !allowHardLimitOverride &&
            previousBackend?.contains("Vulkan", ignoreCase = true) == true

    /** True when the latest three available-RAM samples fit within the allowed spread. */
    fun hasStableAvailableMemory(samples: List<Long>): Boolean {
        if (samples.size < CPU_MEMORY_SETTLING_SAMPLE_COUNT) return false
        val window = samples.takeLast(CPU_MEMORY_SETTLING_SAMPLE_COUNT)
        val minimum = window.minOrNull() ?: return false
        val maximum = window.maxOrNull() ?: return false
        return maximum - minimum <= CPU_MEMORY_SETTLING_MAX_VARIATION_BYTES
    }

    /**
     * Admission-plan values are estimates, not measured peak memory. In particular,
     * [estimatedKvBytes] and [requiredBytes] do not represent measured process PSS,
     * native heap or graphics allocations. Available and total bytes are the system-memory
     * snapshot supplied to this plan.
     */
    data class Plan(
        val effectiveContextTokens: Int, val modelBytes: Long, val estimatedKvBytes: Long,
        val requiredBytes: Long, val availableBytes: Long, val totalBytes: Long,
        val hardLimitBytes: Long, val contextReduced: Boolean, val pressureWarning: Boolean,
        val hardLimitExceeded: Boolean, val overrideApplied: Boolean,
        val strictAvailableMemory: Boolean, val availableLimitExceeded: Boolean,
        val availableLimitOverrideApplied: Boolean
    )

    fun plan(
        modelBytes: Long, requestedContextTokens: Int, availableBytes: Long, totalBytes: Long,
        lowMemory: Boolean = false,
        allowHardLimitOverride: Boolean = false,
        requireAvailableMemory: Boolean = false
    ): Plan {
        require(modelBytes > 0L) { "Model file size must be known and greater than zero" }
        require(requestedContextTokens >= MIN_CONTEXT_TOKENS) {
            "Requested context must be at least $MIN_CONTEXT_TOKENS tokens"
        }
        require(totalBytes > 0L) { "Total system memory could not be determined" }
        require(availableBytes >= 0L) { "Available system memory must not be negative" }

        val hardLimitBytes = percentage85(totalBytes)
        val kvBytesPerToken = maxOf(MIN_KV_BYTES_PER_TOKEN, modelBytes / MODEL_BYTES_PER_KV_ESTIMATE)
        fun kvBytes(contextTokens: Int): Long = saturatedMultiply(kvBytesPerToken, contextTokens.toLong())
        fun requiredBytes(contextTokens: Int): Long =
            saturatedAdd(saturatedAdd(modelBytes, kvBytes(contextTokens)), RUNTIME_HEADROOM_BYTES)

        var contextTokens = requestedContextTokens
        var kvEstimate = kvBytes(contextTokens)
        var required = requiredBytes(contextTokens)
        // Apply the total-RAM ceiling first, reducing the context before rejecting.
        while (required > hardLimitBytes && contextTokens > MIN_CONTEXT_TOKENS) {
            contextTokens = maxOf(MIN_CONTEXT_TOKENS, contextTokens / 2)
            kvEstimate = kvBytes(contextTokens)
            required = requiredBytes(contextTokens)
        }

        val hardLimitExceeded = required > hardLimitBytes
        if (hardLimitExceeded && !allowHardLimitOverride) {
            throw InsufficientModelMemoryException(
                modelBytes = modelBytes,
                requestedContextTokens = requestedContextTokens,
                effectiveContextTokens = contextTokens,
                estimatedKvBytes = kvEstimate,
                requiredBytes = required,
                availableBytes = availableBytes,
                totalBytes = totalBytes,
                hardLimitBytes = hardLimitBytes
            )
        }

        // Reduce context against the current available-memory snapshot. Vulkan and
        // callers that do not request strict admission retain warning-only behavior.
        val initialPressureWarning = lowMemory || availableBytes == 0L || required > availableBytes
        if (availableBytes > 0L && required > availableBytes) {
            while (required > availableBytes && contextTokens > MIN_CONTEXT_TOKENS) {
                contextTokens = maxOf(MIN_CONTEXT_TOKENS, contextTokens / 2)
                kvEstimate = kvBytes(contextTokens)
                required = requiredBytes(contextTokens)
            }
        }
        val availableLimitExceeded = availableBytes == 0L || required > availableBytes
        if (requireAvailableMemory && availableLimitExceeded && !allowHardLimitOverride) {
            throw InsufficientAvailableModelMemoryException(
                modelBytes = modelBytes,
                requestedContextTokens = requestedContextTokens,
                effectiveContextTokens = contextTokens,
                requiredBytes = required,
                availableBytes = availableBytes,
                totalBytes = totalBytes
            )
        }
        val pressureWarning = initialPressureWarning || availableLimitExceeded
        return Plan(
            effectiveContextTokens = contextTokens, modelBytes = modelBytes,
            estimatedKvBytes = kvEstimate, requiredBytes = required,
            availableBytes = availableBytes, totalBytes = totalBytes, hardLimitBytes = hardLimitBytes,
            contextReduced = contextTokens != requestedContextTokens, pressureWarning = pressureWarning,
            hardLimitExceeded = hardLimitExceeded, overrideApplied = hardLimitExceeded && allowHardLimitOverride,
            strictAvailableMemory = requireAvailableMemory,
            availableLimitExceeded = availableLimitExceeded,
            availableLimitOverrideApplied = requireAvailableMemory && availableLimitExceeded && allowHardLimitOverride
        )
    }

    private fun percentage85(totalBytes: Long): Long =
        (totalBytes / 100L) * HARD_LIMIT_PERCENT + ((totalBytes % 100L) * HARD_LIMIT_PERCENT) / 100L
    private fun saturatedMultiply(a: Long, b: Long): Long =
        if (a == 0L || b == 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    private fun saturatedAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

internal class InsufficientAvailableModelMemoryException(
    val modelBytes: Long,
    val requestedContextTokens: Int,
    val effectiveContextTokens: Int,
    val requiredBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long
) : IllegalStateException(
    buildString {
        append("${ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER} ")
        append("CPU-Modellladen aus Sicherheitsgründen abgebrochen: ")
        append("Geschätzter Mindestbedarf=${requiredBytes / (1024L * 1024L)} MiB liegt über ")
        append("dem aktuell verfügbaren Speicher=${availableBytes / (1024L * 1024L)} MiB ")
        append("(Kontext=$effectiveContextTokens, Modell=${modelBytes / (1024L * 1024L)} MiB). ")
        append("Schließe speicherintensive Apps oder bestätige „Trotzdem laden“ ausdrücklich.")
    }
) {
    val reasonCode: String
        get() = REASON_CPU_AVAILABLE_RAM_LIMIT

    fun diagnosticDetails(): String =
        "reason=$reasonCode,modelBytes=$modelBytes,requestedContext=$requestedContextTokens," +
            "effectiveContext=$effectiveContextTokens,requiredBytes=$requiredBytes," +
            "availableBytes=$availableBytes,totalBytes=$totalBytes"

    companion object {
        const val REASON_CPU_AVAILABLE_RAM_LIMIT = "CPU_AVAILABLE_RAM_LIMIT"
    }
}

internal class InsufficientModelMemoryException(
    val modelBytes: Long,
    val requestedContextTokens: Int,
    val effectiveContextTokens: Int,
    val estimatedKvBytes: Long,
    val requiredBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
    val hardLimitBytes: Long
) : IllegalStateException(
    buildString {
        append("${ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER} ")
        append("Geschätzter Bedarf=${requiredBytes / (1024L * 1024L)} MiB liegt auch mit Kontext ")
        append("$effectiveContextTokens über 85% des Gesamtspeichers ")
        append("(${hardLimitBytes / (1024L * 1024L)} MiB). ")
        append("Modell=${modelBytes / (1024L * 1024L)} MiB, ")
        append("KV-Cache=${estimatedKvBytes / (1024L * 1024L)} MiB, ")
        append("Gesamtspeicher=${totalBytes / (1024L * 1024L)} MiB. ")
        append("Bestätige „Trotzdem laden“, um fortzufahren.")
    }
) {
    val reasonCode: String
        get() = REASON_TOTAL_RAM_HARD_LIMIT

    fun diagnosticDetails(): String =
        "reason=$reasonCode,modelBytes=$modelBytes,requestedContext=$requestedContextTokens," +
            "effectiveContext=$effectiveContextTokens,kvEstimateBytes=$estimatedKvBytes," +
            "requiredBytes=$requiredBytes,availableBytes=$availableBytes," +
            "totalBytes=$totalBytes,hardLimitBytes=$hardLimitBytes"

    companion object {
        const val REASON_TOTAL_RAM_HARD_LIMIT = "TOTAL_RAM_HARD_LIMIT"
    }
}

internal class ModelMemorySettlingTimeoutException(
    val previousBackend: String,
    val lastAvailableBytes: Long,
    val requiredBytes: Long,
    val elapsedMs: Long,
    val lowMemory: Boolean,
    val stableSampleCount: Int,
    val stableRangeBytes: Long?
) : IllegalStateException(
    buildString {
        append("${ModelMemoryGuard.OVERRIDE_REQUIRED_MARKER} ")
        append("CPU-Modellladen nach Vulkan-Unload abgebrochen: ")
        append("Der verfügbare RAM wurde innerhalb von ${elapsedMs} ms nicht stabil. ")
        append("Verfügbar=${lastAvailableBytes / (1024L * 1024L)} MiB, ")
        append("Mindestbedarf=${requiredBytes / (1024L * 1024L)} MiB, ")
        append("lowMemory=$lowMemory, samples=$stableSampleCount, rangeBytes=${stableRangeBytes ?: "unknown"}.")
    }
) {
    fun diagnosticDetails(): String =
        "reason=CPU_MEMORY_SETTLING_TIMEOUT,previousBackend=$previousBackend," +
            "lastAvailableBytes=$lastAvailableBytes,requiredBytes=$requiredBytes," +
            "elapsedMs=$elapsedMs,lowMemory=$lowMemory," +
            "stableSampleCount=$stableSampleCount,stableRangeBytes=${stableRangeBytes ?: -1L}"
}
