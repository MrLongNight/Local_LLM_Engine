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
 */
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val maxPredictTokens: Int = 1024,
    val temperature: Float? = null,
    val topP: Float? = null
) : Parcelable {
    init {
        require(messages.isNotEmpty()) { "InferenceRequest messages must not be empty" }
        require(maxPredictTokens > 0) { "maxPredictTokens must be greater than zero" }
        require(temperature == null || (temperature.isFinite() && temperature >= 0f)) {
            "temperature override must be finite and non-negative"
        }
        require(topP == null || (topP.isFinite() && topP in 0f..1f)) {
            "topP override must be finite and between 0 and 1"
        }
    }

    constructor(parcel: Parcel) : this(
        messages = parcel.createTypedArrayList(ChatMessage.CREATOR) ?: emptyList(),
        maxPredictTokens = parcel.readInt(),
        temperature = if (parcel.readByte() != 0.toByte()) parcel.readFloat() else null,
        topP = if (parcel.readByte() != 0.toByte()) parcel.readFloat() else null
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
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<InferenceRequest> = object : Parcelable.Creator<InferenceRequest> {
            override fun createFromParcel(parcel: Parcel): InferenceRequest = InferenceRequest(parcel)
            override fun newArray(size: Int): Array<InferenceRequest?> = arrayOfNulls(size)
        }

        fun single(userPrompt: String, systemPrompt: String? = null, maxPredictTokens: Int = 1024): InferenceRequest {
            val list = mutableListOf<ChatMessage>()
            if (!systemPrompt.isNullOrBlank()) {
                list.add(ChatMessage.system(systemPrompt))
            }
            list.add(ChatMessage.user(userPrompt))
            return InferenceRequest(list, maxPredictTokens)
        }
    }
}
