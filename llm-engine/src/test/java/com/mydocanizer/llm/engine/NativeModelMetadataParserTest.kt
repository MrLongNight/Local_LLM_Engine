package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeModelMetadataParserTest {

    @Test
    fun parsesArchitectureAndKeepsSemicolonDelimitedBackendMetadata() {
        val parsed = NativeModelMetadataParser.parse(
            rawMetadata = "Qwen;3500000000;131072;123456;ARCH=QWEN35;Vulkan;device=Mali-G57;gpuLayers=37",
            fallbackBackend = "CPU",
            fallbackSizeBytes = 999L
        )

        assertEquals("Qwen", parsed.description)
        assertEquals(3_500_000_000L, parsed.parameterCount)
        assertEquals(131072, parsed.contextLengthTrain)
        assertEquals(123456L, parsed.sizeBytes)
        assertEquals("QWEN35", parsed.architecture)
        assertEquals("Vulkan;device=Mali-G57;gpuLayers=37", parsed.activeBackend)
    }

    @Test
    fun architectureMarkerIsCaseInsensitiveAndItsValueIsStripped() {
        val parsed = NativeModelMetadataParser.parse(
            rawMetadata = "Qwen;1;2;3;arch=QWEN35MOE;Vulkan;device=Mali-G57",
            fallbackBackend = "CPU",
            fallbackSizeBytes = 99L
        )

        assertEquals("QWEN35MOE", parsed.architecture)
        assertEquals("Vulkan;device=Mali-G57", parsed.activeBackend)
    }

    @Test
    fun architectureValuesRemainOpaqueForSupportedModelFamilies() {
        val architectures = listOf("QWEN3", "QWEN35", "QWEN35MOE", "GEMMA4", "LLAMA")
        architectures.forEach { architecture ->
            val parsed = NativeModelMetadataParser.parse(
                rawMetadata = "model;1;2;3;ARCH=$architecture;CPU",
                fallbackBackend = "fallback",
                fallbackSizeBytes = 99L
            )
            assertEquals(architecture, parsed.architecture)
        }
    }

    @Test
    fun legacyMetadataKeepsBackendSemicolonsAndUsesGgufArchitecture() {
        val parsed = NativeModelMetadataParser.parse(
            rawMetadata = "Legacy;42;4096;8192;Vulkan;device=legacy",
            fallbackBackend = "CPU",
            fallbackSizeBytes = 99L
        )

        assertEquals("GGUF", parsed.architecture)
        assertEquals("Vulkan;device=legacy", parsed.activeBackend)
    }

    @Test
    fun invalidNumericFieldsUseSafeFallbacksAndBlankArchitectureUsesGguf() {
        val parsed = NativeModelMetadataParser.parse(
            rawMetadata = "model;invalid;invalid;invalid;ARCH=;CPU",
            fallbackBackend = "fallback",
            fallbackSizeBytes = 777L
        )

        assertEquals(0L, parsed.parameterCount)
        assertEquals(0, parsed.contextLengthTrain)
        assertEquals(777L, parsed.sizeBytes)
        assertEquals("GGUF", parsed.architecture)
        assertEquals("CPU", parsed.activeBackend)
    }
}
