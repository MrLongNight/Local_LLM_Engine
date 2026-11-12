package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

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
    ERROR,
    /** Timeout during service binding */
    TIMEOUT_SERVICE_BIND,
    /** Timeout while loading model into memory */
    TIMEOUT_MODEL_LOAD,
    /** Timeout during prompt prefill */
    TIMEOUT_PREFILL,
    /** Timeout waiting for first token */
    TIMEOUT_FIRST_TOKEN,
    /** Timeout between consecutive tokens */
    TIMEOUT_INTER_TOKEN,
    /** Total inference duration exceeded */
    TIMEOUT_TOTAL_INFERENCE,
    /** Timeout during service reconnection */
    TIMEOUT_RECONNECT;

    val isTimeout: Boolean
        get() = this in setOf(
            TIMEOUT_SERVICE_BIND,
            TIMEOUT_MODEL_LOAD,
            TIMEOUT_PREFILL,
            TIMEOUT_FIRST_TOKEN,
            TIMEOUT_INTER_TOKEN,
            TIMEOUT_TOTAL_INFERENCE,
            TIMEOUT_RECONNECT
        )
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
    val finishReason: FinishReason,
    /** Scheduler graph splits reported by llama.cpp, or -1 when unavailable. */
    val graphSplits: Int = -1,
    /** Binder inference request identity, matching INFERENCE_<runId> RAM samples. */
    val diagnosticRunId: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        text = parcel.readString() ?: "",
        tokensGenerated = parcel.readInt(),
        durationMs = parcel.readLong(),
        tokensPerSecond = parcel.readDouble(),
        finishReason = FinishReason.valueOf(parcel.readString() ?: FinishReason.STOP.name),
        graphSplits = if (parcel.dataAvail() >= Int.SIZE_BYTES) parcel.readInt() else -1,
        diagnosticRunId = if (parcel.dataAvail() > 0) parcel.readString().orEmpty() else ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(text)
        parcel.writeInt(tokensGenerated)
        parcel.writeLong(durationMs)
        parcel.writeDouble(tokensPerSecond)
        parcel.writeString(finishReason.name)
        parcel.writeInt(graphSplits)
        parcel.writeString(diagnosticRunId)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<InferenceResult> = object : Parcelable.Creator<InferenceResult> {
            override fun createFromParcel(parcel: Parcel): InferenceResult = InferenceResult(parcel)
            override fun newArray(size: Int): Array<InferenceResult?> = arrayOfNulls(size)
        }
    }
}
