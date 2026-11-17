package com.mydocanizer.llm.engine

/**
 * Streamed events emitted during LLM inference.
 */
sealed interface InferenceEvent {
    /**
     * Emitted immediately when prompt ingestion begins.
     *
     * @property promptMessageCount Number of messages being processed.
     */
    data class Started(val promptMessageCount: Int) : InferenceEvent

    /**
     * Emitted for each newly generated UTF-8 token piece.
     *
     * @property token Decoded token piece.
     * @property tokenIndex 0-indexed count of tokens generated so far.
     */
    data class Token(val token: String, val tokenIndex: Int) : InferenceEvent

    enum class ProgressPhase {
        PROMPT_PREPARATION,
        PROMPT_EVALUATION,
        WAITING_FOR_FIRST_TOKEN,
        GENERATING
    }

    /**
     * Measured progress. Prompt counts are exact llama.cpp token counts. The ETA during
     * generation is only a projection to the configured output-token cap, not a promise
     * that the model will use the entire cap.
     */
    data class Progress(
        val tokensGenerated: Int,
        val elapsedMs: Long,
        val phase: ProgressPhase = ProgressPhase.GENERATING,
        val promptTokensProcessed: Int = 0,
        val promptTokensTotal: Int = 0,
        val tokensPerSecond: Double = 0.0,
        val estimatedRemainingMs: Long? = null
    ) : InferenceEvent

    /**
     * Emitted when generation successfully finishes.
     *
     * @property result Final summary result.
     */
    data class Completed(val result: InferenceResult) : InferenceEvent

    /**
     * Emitted when inference was cancelled cooperatively.
     *
     * @property partialText Text generated up until cancellation.
     * @property tokensGenerated Number of tokens emitted before cancellation.
     */
    data class Cancelled(
        val partialText: String,
        val tokensGenerated: Int
    ) : InferenceEvent

    /**
     * Emitted if a fatal error occurs during inference.
     *
     * @property throwable The exception encountered.
     * @property partialText Text accumulated before the error.
     */
    data class Error(
        val throwable: Throwable,
        val partialText: String = ""
    ) : InferenceEvent
}
