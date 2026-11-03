package com.mydocanizer.llm.engine

/**
 * Encapsulates a multi-message chat or single prompt inference request.
 *
 * @property messages Chronological sequence of messages in this conversation.
 * @property maxPredictTokens Maximum tokens to generate for this turn.
 * @property temperature Custom temperature override for this request, or null for engine default.
 */
data class InferenceRequest(
    val messages: List<ChatMessage>,
    val maxPredictTokens: Int = 1024,
    val temperature: Float? = null
) {
    init {
        require(messages.isNotEmpty()) { "InferenceRequest messages must not be empty" }
        require(maxPredictTokens > 0) { "maxPredictTokens must be greater than zero" }
        require(temperature == null || (temperature.isFinite() && temperature >= 0f)) {
            "temperature must be a finite, non-negative value"
        }
    }

    companion object {
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
