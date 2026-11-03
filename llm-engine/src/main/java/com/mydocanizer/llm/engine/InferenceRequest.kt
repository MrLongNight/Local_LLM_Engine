package com.mydocanizer.llm.engine

/**
 * Encapsulates a multi-message chat or single prompt inference request.
 *
 * @property messages Chronological sequence of messages in this conversation.
 * @property maxPredictTokens Maximum tokens for this turn; null uses the active model's EngineConfig default.
 * @property temperature Custom temperature override for this request, or null for engine default.
 * @property topP Custom nucleus sampling threshold, or null for the model-load default.
 */
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val maxPredictTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null
) {
    init {
        require(messages.isNotEmpty()) { "InferenceRequest messages must not be empty" }
        require(maxPredictTokens == null || maxPredictTokens > 0) {
            "maxPredictTokens must be null or greater than zero"
        }
        require(temperature == null || (temperature.isFinite() && temperature >= 0f)) {
            "temperature must be a finite, non-negative value"
        }
        require(topP == null || (topP.isFinite() && topP in 0f..1f)) {
            "topP must be null or a finite value between 0 and 1"
        }
    }

    companion object {
        fun single(userPrompt: String, systemPrompt: String? = null, maxPredictTokens: Int? = null): InferenceRequest {
            val list = mutableListOf<ChatMessage>()
            if (!systemPrompt.isNullOrBlank()) {
                list.add(ChatMessage.system(systemPrompt))
            }
            list.add(ChatMessage.user(userPrompt))
            return InferenceRequest(list, maxPredictTokens)
        }
    }
}
