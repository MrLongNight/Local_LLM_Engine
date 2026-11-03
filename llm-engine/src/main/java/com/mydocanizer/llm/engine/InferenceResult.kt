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
) : Parcelable {
    constructor(parcel: Parcel) : this(
        text = parcel.readString() ?: "",
        tokensGenerated = parcel.readInt(),
        durationMs = parcel.readLong(),
        tokensPerSecond = parcel.readDouble(),
        finishReason = FinishReason.valueOf(parcel.readString() ?: FinishReason.STOP.name)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(text)
        parcel.writeInt(tokensGenerated)
        parcel.writeLong(durationMs)
        parcel.writeDouble(tokensPerSecond)
        parcel.writeString(finishReason.name)
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
