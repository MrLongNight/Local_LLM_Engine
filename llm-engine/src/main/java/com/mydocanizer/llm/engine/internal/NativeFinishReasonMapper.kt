package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.FinishReason

/** Maps the stable native terminal codes into the public inference result contract. */
internal object NativeFinishReasonMapper {
    fun fromNativeCode(rawReason: Int, cancellationRequested: Boolean): FinishReason =
        when {
            cancellationRequested -> FinishReason.CANCELLED
            rawReason == 0 -> FinishReason.STOP
            rawReason == 1 -> FinishReason.MAX_TOKENS
            rawReason == 2 -> FinishReason.CANCELLED
            else -> FinishReason.ERROR
        }
}
