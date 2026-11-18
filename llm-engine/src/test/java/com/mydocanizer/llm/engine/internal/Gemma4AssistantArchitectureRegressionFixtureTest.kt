package com.mydocanizer.llm.engine.internal

import com.mydocanizer.llm.engine.NativeModelMetadataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Gemma4AssistantArchitectureRegressionFixtureTest {

    @Test
    fun keepsAssistantDraftArchitectureDistinctFromGemma4TextArchitecture() {
        val text = NativeModelMetadataParser.parse(
            rawMetadata = "Gemma 4 E2B;2000000000;131072;3430000000;ARCH=gemma4;CPU",
            fallbackBackend = "fallback",
            fallbackSizeBytes = 1L
        )
        val assistant = NativeModelMetadataParser.parse(
            rawMetadata = "Gemma 4 E2B Assistant;100000000;131072;250000000;ARCH=gemma4-assistant;CPU",
            fallbackBackend = "fallback",
            fallbackSizeBytes = 2L
        )

        assertEquals("gemma4", text.architecture)
        assertEquals("gemma4-assistant", assistant.architecture)
        assertNotEquals(text.architecture, assistant.architecture)
    }
}
