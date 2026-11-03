package com.mydocanizer.llm.engine

/**
 * Standard roles for multi-turn conversational chat.
 */
enum class ChatRole(val roleName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromString(value: String): ChatRole =
            entries.firstOrNull { it.roleName.equals(value, ignoreCase = true) } ?: USER
    }
}

/**
 * A single conversational turn in a multi-message chat thread.
 *
 * @property role The author of the message (system, user, assistant).
 * @property content The textual message payload.
 * @property timestamp Epoch millisecond timestamp of the message creation.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun system(content: String): ChatMessage = ChatMessage(ChatRole.SYSTEM, content)
        fun user(content: String): ChatMessage = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String): ChatMessage = ChatMessage(ChatRole.ASSISTANT, content)
    }
}
