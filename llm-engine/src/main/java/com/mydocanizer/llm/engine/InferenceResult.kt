package com.mydocanizer.llm.engine

/**
 * Reason why token generation concluded.
 */
enum class FinishReason {
    /** Generated end-of-generation or stop token */
    STOP,
    /** Hit maxPredictTokens limit */
    MAX_TOKENS,
    /** User cancelled inference */
    CANCELLED,
    /** Terminated due to error */
    ERROR
}

/**
 * Complete outcome of an inference pass.
 *
 * @property text The generated text from the model.
 * @property tokensGenerated Number of tokens output by the model.
 * @property durationMs Total wall-clock time spent in milliseconds.
 * @property tokensPerSecond Generation speed in tokens per second.
 * @property finishReason Reason for conclusion.
 */
data class InferenceResult(
    val text: String,
    val tokensGenerated: Int,
    val durationMs: Long,
    val tokensPerSecond: Double,
    val finishReason: FinishReason
)
