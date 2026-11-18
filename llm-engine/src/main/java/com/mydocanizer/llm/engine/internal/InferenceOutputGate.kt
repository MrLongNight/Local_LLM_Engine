package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.FinishReason

/** Common, model-agnostic correctness gate for visible model output. */
internal object InferenceOutputGate {
    data class Evaluation(val accepted: Boolean, val truncated: Boolean, val reason: String?)

    /** Null means the text contains only well-formed Unicode scalar values and renderable controls. */
    fun visibleTextFailure(text: String): String? {
        var index = 0
        while (index < text.length) {
            val value = text[index].code
            when {
                value in 0xD800..0xDBFF -> {
                    if (index + 1 >= text.length || text[index + 1].code !in 0xDC00..0xDFFF) {
                        return "INVALID_UTF16_SURROGATE"
                    }
                    index += 2
                    continue
                }
                value in 0xDC00..0xDFFF -> return "INVALID_UTF16_SURROGATE"
                value == 0 ||
                    value in 0x01..0x08 ||
                    value in 0x0B..0x0C ||
                    value in 0x0E..0x1F ||
                    value in 0x7F..0x9F -> return "UNEXPECTED_CONTROL_CHARACTER"
            }
            index++
        }
        return null
    }

    fun evaluate(text: String, tokensGenerated: Int, finishReason: FinishReason): Evaluation {
        visibleTextFailure(text)?.let { return Evaluation(false, false, it) }
        if (text.isBlank()) return Evaluation(false, false, "EMPTY_OUTPUT")
        if (tokensGenerated <= 0) return Evaluation(false, false, "NO_OUTPUT_TOKENS")
        return when (finishReason) {
            FinishReason.STOP -> Evaluation(true, false, null)
            // Keep MAX_TOKENS visible to callers, but never mislabel it as a normal stop.
            FinishReason.MAX_TOKENS -> Evaluation(true, true, "MAX_TOKENS")
            else -> Evaluation(false, false, "UNEXPECTED_FINISH_REASON_${finishReason.name}")
        }
    }
}
