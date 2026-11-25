package com.mydocanizer.llm.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeGpuOffloadParserTest {
    @Test
    fun parsesLlamaCppActualOffloadedLayerCount() {
        val parsed = NativeGpuOffloadParser.parse(
            "[NATIVE] llama_model_load: offloaded 37/37 layers to GPU"
        )

        assertNotNull(parsed)
        assertEquals(37, parsed?.allocatedLayers)
        assertEquals(37, parsed?.totalLayers)
    }

    @Test
    fun rejectsMalformedOrImpossibleOffloadCounts() {
        assertNull(NativeGpuOffloadParser.parse("llama_model_load: offloaded 38/37 layers to GPU"))
        assertNull(NativeGpuOffloadParser.parse("llama_model_load: offloaded -1/37 layers to GPU"))
        assertNull(NativeGpuOffloadParser.parse("llama_model_load: model loaded successfully"))
    }

    @Test
    fun usesTheLatestOffloadRecordFromOneLoadAttempt() {
        val parsed = NativeGpuOffloadParser.parse(
            """
            llama_model_load: offloaded 18/37 layers to GPU
            some unrelated line
            llama_model_load: offloaded 37/37 layers to GPU
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(37, parsed?.allocatedLayers)
        assertEquals(37, parsed?.totalLayers)
    }
}
