package com.example.ui.chat

import com.mydocanizer.llm.engine.ChatMessage

/**
 * Builds the chronological prompt context for one chat turn.
 *
 * The UI transcript may contain only user/assistant turns; the system prompt
 * is injected once at the front of every inference request and is never stored
 * as a synthetic visible chat message.
 */
internal object ChatConversationRequestBuilder {
    fun build(systemPrompt: String, conversation: List<ChatMessage>): List<ChatMessage> =
        buildList(conversation.size + if (systemPrompt.isNotBlank()) 1 else 0) {
            if (systemPrompt.isNotBlank()) add(ChatMessage.system(systemPrompt))
            addAll(conversation)
        }
}
