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

    /**
     * Emitted periodically during generation with token metrics.
     *
     * @property tokensGenerated Number of tokens generated so far.
     * @property elapsedMs Elapsed time in milliseconds since generation started.
     */
    data class Progress(val tokensGenerated: Int, val elapsedMs: Long) : InferenceEvent

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
