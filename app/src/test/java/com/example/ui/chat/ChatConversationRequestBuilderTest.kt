package com.example.ui.chat

import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatConversationRequestBuilderTest {

    @Test
    fun build_keepsSystemPromptAndCompleteConversationInChronologicalOrder() {
        val conversation = listOf(
            ChatMessage.user("Merke dir: Projektname ist VinylKultur."),
            ChatMessage.assistant("Verstanden. Das Projekt heißt VinylKultur."),
            ChatMessage.user("Wie heißt das Projekt?")
        )

        val request = ChatConversationRequestBuilder.build(
            systemPrompt = "Du bist ein hilfreicher Assistent.",
            conversation = conversation
        )

        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            request.map { it.role }
        )
        assertEquals(
            listOf(
                "Du bist ein hilfreicher Assistent.",
                "Merke dir: Projektname ist VinylKultur.",
                "Verstanden. Das Projekt heißt VinylKultur.",
                "Wie heißt das Projekt?"
            ),
            request.map { it.content }
        )
        assertEquals(conversation, request.drop(1))
    }

    @Test
    fun build_withoutSystemPrompt_doesNotInsertSyntheticWelcomeMessage() {
        val conversation = listOf(ChatMessage.user("Hallo"))

        val request = ChatConversationRequestBuilder.build("", conversation)

        assertEquals(conversation, request)
        assertFalse(request.any { it.role == ChatRole.SYSTEM })
    }
}
