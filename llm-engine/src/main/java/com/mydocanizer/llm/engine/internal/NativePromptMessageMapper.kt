package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.ChatMessage

/**
 * Ordered JNI input arrays produced from the public chat-message contract.
 *
 * Keeping this adapter in one place makes role ordering and payload preservation
 * independently testable without loading a native model.
 */
internal data class NativePromptMessages(
    val roles: Array<String>,
    val contents: Array<String>
)

internal object NativePromptMessageMapper {
    fun map(messages: List<ChatMessage>): NativePromptMessages =
        NativePromptMessages(
            roles = messages.map { it.role.roleName }.toTypedArray(),
            contents = messages.map { it.content }.toTypedArray()
        )
}
