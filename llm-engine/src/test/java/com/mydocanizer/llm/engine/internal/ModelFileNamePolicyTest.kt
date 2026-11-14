package com.mydocanizer.llm.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFileNamePolicyTest {

    @Test
    fun preservesOriginalModelFileNameIncludingSpacesAndUnicode() {
        val original = "Qwen3.5-4B Instruct (Q4_K_M) – Deutsch.gguf"
        assertEquals(original, ModelFileNamePolicy.safeOriginalName(original))
    }

    @Test
    fun removesOnlyLegacyContentHashPrefixForDisplay() {
        val original = "Qwen3-4B-Instruct-Q4_K_M.gguf"
        val stored = "0123456789abcdef".repeat(4) + "-$original"
        assertEquals(original, ModelFileNamePolicy.displayName(stored))
    }

    @Test
    fun leavesNewOriginalFileNamesUnchangedForDisplay() {
        val original = "Gemma 4 4B Q4_K_M.gguf"
        assertEquals(original, ModelFileNamePolicy.displayName(original))
    }

    @Test
    fun stripsDirectoryComponentsAndAddsMissingExtension() {
        assertEquals(
            "Llama 3.1 8B.gguf",
            ModelFileNamePolicy.safeOriginalName("/models/Llama 3.1 8B")
        )
    }

    @Test
    fun substitutesEmptyOrUnsafeNamesWithSafeFallback() {
        assertEquals("model.gguf", ModelFileNamePolicy.safeOriginalName("../"))
    }
}
