package com.mydocanizer.llm.engine.internal

/**
 * Conservative pre-load memory gate. It charges the GGUF file size, a model-size-scaled
 * KV allowance and runtime headroom. It is an estimate, not architecture-exact metadata,
 * but prevents obviously unsafe loads and lowers n_ctx before native allocation.
 */
internal object ModelMemoryGuard {
    const val MIN_CONTEXT_TOKENS = 256
    private const val MIN_KV_BYTES_PER_TOKEN = 128L * 1024L
    private const val MODEL_BYTES_PER_KV_ESTIMATE = 10_000L
    private const val RUNTIME_HEADROOM_BYTES = 512L * 1024L * 1024L

    data class Plan(
        val effectiveContextTokens: Int,
        val modelBytes: Long,
        val estimatedKvBytes: Long,
        val requiredBytes: Long,
        val availableBytes: Long,
        val contextReduced: Boolean
    )

    fun plan(
        modelBytes: Long,
        requestedContextTokens: Int,
        availableBytes: Long,
        lowMemory: Boolean = false
    ): Plan {
        require(modelBytes > 0L) { "Model file size must be known and greater than zero" }
        require(requestedContextTokens >= MIN_CONTEXT_TOKENS) {
            "Requested context must be at least $MIN_CONTEXT_TOKENS tokens"
        }
        require(availableBytes > 0L) { "Available system memory could not be determined" }

        val kvBytesPerToken = maxOf(MIN_KV_BYTES_PER_TOKEN, modelBytes / MODEL_BYTES_PER_KV_ESTIMATE)
        var contextTokens = requestedContextTokens
        var kvBytes = saturatedMultiply(kvBytesPerToken, contextTokens.toLong())
        var required = saturatedAdd(saturatedAdd(modelBytes, kvBytes), RUNTIME_HEADROOM_BYTES)

        while ((lowMemory || required > availableBytes) && contextTokens > MIN_CONTEXT_TOKENS) {
            contextTokens = maxOf(MIN_CONTEXT_TOKENS, contextTokens / 2)
            kvBytes = saturatedMultiply(kvBytesPerToken, contextTokens.toLong())
            required = saturatedAdd(saturatedAdd(modelBytes, kvBytes), RUNTIME_HEADROOM_BYTES)
            if (!lowMemory && required <= availableBytes) break
        }

        if (lowMemory || required > availableBytes) {
            throw InsufficientModelMemoryException(
                "Nicht genügend freier Arbeitsspeicher zum Laden dieses Modells. " +
                    "Modell=${modelBytes / MIB} MiB, geschätzter KV-Cache=${kvBytes / MIB} MiB, " +
                    "benötigt=${required / MIB} MiB, verfügbar=${availableBytes / MIB} MiB. " +
                    "Auch mit reduziertem Kontext von $contextTokens Tokens ist der sichere Ladevorgang nicht möglich."
            )
        }

        return Plan(
            effectiveContextTokens = contextTokens,
            modelBytes = modelBytes,
            estimatedKvBytes = kvBytes,
            requiredBytes = required,
            availableBytes = availableBytes,
            contextReduced = contextTokens != requestedContextTokens
        )
    }

    private fun saturatedMultiply(a: Long, b: Long): Long =
        if (a == 0L || b == 0L) 0L
        else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE
        else a * b

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private const val MIB = 1024L * 1024L
}

internal class InsufficientModelMemoryException(message: String) : IllegalStateException(message)
