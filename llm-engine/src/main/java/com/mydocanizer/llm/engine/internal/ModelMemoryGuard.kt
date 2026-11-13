package com.mydocanizer.llm.engine.internal

/**
 * Model-load guard that distinguishes the reclaimable GGUF page cache / shared UMA heap
 * from process-private allocations. Only the conservative total-RAM estimate is a hard
 * gate; available RAM and Android's lowMemory flag are warning/context-reduction signals.
 */
internal object ModelMemoryGuard {
    const val MIN_CONTEXT_TOKENS = 256
    private const val MIN_KV_BYTES_PER_TOKEN = 128L * 1024L
    private const val MODEL_BYTES_PER_KV_ESTIMATE = 10_000L
    private const val RUNTIME_HEADROOM_BYTES = 256L * 1024L * 1024L
    private const val HARD_LIMIT_PERCENT = 85L
    private const val MIB = 1024L * 1024L
    const val OVERRIDE_REQUIRED_MARKER = "MEMORY_GUARD_OVERRIDE_REQUIRED:"

    data class Plan(
        val effectiveContextTokens: Int, val modelBytes: Long, val estimatedKvBytes: Long,
        val requiredBytes: Long, val availableBytes: Long, val totalBytes: Long,
        val hardLimitBytes: Long, val contextReduced: Boolean, val pressureWarning: Boolean,
        val hardLimitExceeded: Boolean, val overrideApplied: Boolean
    )

    fun plan(
        modelBytes: Long, requestedContextTokens: Int, availableBytes: Long, totalBytes: Long,
        lowMemory: Boolean = false, allowHardLimitOverride: Boolean = false
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
        // The only hard boundary is 85% of total RAM. Try smaller contexts first.
        while (required > hardLimitBytes && contextTokens > MIN_CONTEXT_TOKENS) {
            contextTokens = maxOf(MIN_CONTEXT_TOKENS, contextTokens / 2)
            kvEstimate = kvBytes(contextTokens)
            required = requiredBytes(contextTokens)
        }

        val hardLimitExceeded = required > hardLimitBytes
        if (hardLimitExceeded && !allowHardLimitOverride) {
            throw InsufficientModelMemoryException(
                "$OVERRIDE_REQUIRED_MARKER " +
                    "Geschätzter Bedarf=${required / MIB} MiB liegt auch mit Kontext $contextTokens " +
                    "über 85% des Gesamtspeichers (${hardLimitBytes / MIB} MiB). " +
                    "Modell=${modelBytes / MIB} MiB, KV-Cache=${kvEstimate / MIB} MiB, " +
                    "Gesamtspeicher=${totalBytes / MIB} MiB. Bestätige „Trotzdem laden“, um fortzufahren."
            )
        }

        // availMem is soft: reclaimable file cache and the UMA Vulkan heap make it an
        // unreliable hard boundary. Reduce context where possible; never fail on it.
        val initialPressureWarning = lowMemory || availableBytes == 0L || required > availableBytes
        if (availableBytes > 0L && required > availableBytes) {
            while (required > availableBytes && contextTokens > MIN_CONTEXT_TOKENS) {
                contextTokens = maxOf(MIN_CONTEXT_TOKENS, contextTokens / 2)
                kvEstimate = kvBytes(contextTokens)
                required = requiredBytes(contextTokens)
            }
        }
        val pressureWarning = initialPressureWarning || required > availableBytes
        return Plan(
            effectiveContextTokens = contextTokens, modelBytes = modelBytes,
            estimatedKvBytes = kvEstimate, requiredBytes = required,
            availableBytes = availableBytes, totalBytes = totalBytes, hardLimitBytes = hardLimitBytes,
            contextReduced = contextTokens != requestedContextTokens, pressureWarning = pressureWarning,
            hardLimitExceeded = hardLimitExceeded, overrideApplied = hardLimitExceeded && allowHardLimitOverride
        )
    }

    private fun percentage85(totalBytes: Long): Long =
        (totalBytes / 100L) * HARD_LIMIT_PERCENT + ((totalBytes % 100L) * HARD_LIMIT_PERCENT) / 100L
    private fun saturatedMultiply(a: Long, b: Long): Long =
        if (a == 0L || b == 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    private fun saturatedAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

internal class InsufficientModelMemoryException(message: String) : IllegalStateException(message)
