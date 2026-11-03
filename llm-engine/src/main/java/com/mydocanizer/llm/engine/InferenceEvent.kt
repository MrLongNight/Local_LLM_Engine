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
     * @property tokenIndex 0-indexed index of emitted text chunks. A single model token
     * may produce no visible UTF-8 chunk while a multibyte code point is being assembled;
     * use InferenceResult.tokensGenerated for the native model-token count.
     */
    data class Token(val token: String, val tokenIndex: Int) : InferenceEvent

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
     * @property tokensGenerated Native model-token count generated before cancellation.
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
