package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

/**
 * Encapsulates a multi-message chat or single prompt inference request.
 *
 * @property messages Chronological sequence of messages in this conversation.
 * @property maxPredictTokens Maximum tokens to generate for this turn.
 * @property temperature Custom temperature override for this request, or null for engine default.
 * @property topP Custom top-p nucleus sampling override, or null for engine default.
 * @property enableThinking Whether the model's thinking mode is enabled for this request.
 * @property grammar Optional GBNF output grammar; the entry rule must be named "root".
 */
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val maxPredictTokens: Int = 1024,
    val temperature: Float? = null,
    val topP: Float? = null,
    val timeoutConfig: TimeoutConfig? = null,
    val enableThinking: Boolean = false,
    val grammar: String? = null
) : Parcelable {
    init {
        require(messages.isNotEmpty()) { "InferenceRequest messages must not be empty" }
        require(maxPredictTokens > 0) { "maxPredictTokens must be greater than zero" }
        require(temperature == null || (temperature.isFinite() && temperature >= 0f)) {
            "temperature must be finite and non-negative"
        }
        require(topP == null || (topP.isFinite() && topP in 0f..1f)) {
            "topP must be finite and between 0 and 1"
        }
        require(grammar == null || grammar.length <= MAX_GRAMMAR_LENGTH_CHARS) {
            "grammar must not exceed $MAX_GRAMMAR_LENGTH_CHARS characters"
        }
    }

    constructor(parcel: Parcel) : this(
        messages = parcel.createTypedArrayList(ChatMessage.CREATOR) ?: emptyList(),
        maxPredictTokens = parcel.readInt(),
        temperature = if (parcel.readByte() != 0.toByte()) parcel.readFloat() else null,
        topP = if (parcel.readByte() != 0.toByte()) parcel.readFloat() else null,
        timeoutConfig = if (android.os.Build.VERSION.SDK_INT >= 33) {
            parcel.readParcelable(TimeoutConfig::class.java.classLoader, TimeoutConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(TimeoutConfig::class.java.classLoader)
        },
        // Appended fields preserve compatibility with older Parcel writers.
        enableThinking = parcel.dataAvail() >= Int.SIZE_BYTES && parcel.readInt() != 0,
        grammar = if (parcel.dataAvail() > 0) parcel.readString() else null
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeTypedList(messages)
        parcel.writeInt(maxPredictTokens)
        if (temperature != null) {
            parcel.writeByte(1)
            parcel.writeFloat(temperature)
        } else {
            parcel.writeByte(0)
        }
        if (topP != null) {
            parcel.writeByte(1)
            parcel.writeFloat(topP)
        } else {
            parcel.writeByte(0)
        }
        parcel.writeParcelable(timeoutConfig, flags)
        parcel.writeInt(if (enableThinking) 1 else 0)
        parcel.writeString(grammar)
    }

    override fun describeContents(): Int = 0

    companion object {
        private const val MAX_GRAMMAR_LENGTH_CHARS = 64 * 1024

        @JvmField
        val CREATOR: Parcelable.Creator<InferenceRequest> = object : Parcelable.Creator<InferenceRequest> {
            override fun createFromParcel(parcel: Parcel): InferenceRequest = InferenceRequest(parcel)
            override fun newArray(size: Int): Array<InferenceRequest?> = arrayOfNulls(size)
        }

        fun single(
            userPrompt: String,
            systemPrompt: String? = null,
            maxPredictTokens: Int = 1024,
            enableThinking: Boolean = false,
            grammar: String? = null
        ): InferenceRequest {
            val list = mutableListOf<ChatMessage>()
            if (!systemPrompt.isNullOrBlank()) {
                list.add(ChatMessage.system(systemPrompt))
            }
            list.add(ChatMessage.user(userPrompt))
            return InferenceRequest(
                list,
                maxPredictTokens,
                enableThinking = enableThinking,
                grammar = grammar
            )
        }
    }
}
