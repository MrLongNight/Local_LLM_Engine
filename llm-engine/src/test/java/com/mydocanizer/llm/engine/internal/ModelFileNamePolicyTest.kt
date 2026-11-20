package com.mydocanizer.llm.engine.internal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun reusesPrivateImportDigestWhenDescriptorSymlinkResolvesToImportedModel() {
        val root = Files.createTempDirectory("model-file-name-policy").toFile()
        try {
            val privateModelsDir = File(root, "files/models").apply { mkdirs() }
            val digest = "a".repeat(64)
            val imported = File(privateModelsDir, "$digest-Qwen3-4B-Instruct-2507-Q4_K_M.gguf")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val descriptorPath = File(root, "fd-124")
            Files.createSymbolicLink(descriptorPath.toPath(), imported.toPath())

            assertEquals(
                digest,
                ModelFileNamePolicy.trustedPrivateImportSha256(descriptorPath, privateModelsDir)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun neverTrustsHashLikeNamesOutsidePrivateModelDirectoryOrMalformedPrefixes() {
        val root = Files.createTempDirectory("model-file-name-policy").toFile()
        try {
            val privateModelsDir = File(root, "files/models").apply { mkdirs() }
            val externalDir = File(root, "external").apply { mkdirs() }
            val digest = "b".repeat(64)
            val external = File(externalDir, "$digest-model.gguf").apply { writeBytes(byteArrayOf(1)) }
            val malformed = File(privateModelsDir, "not-a-full-content-hash-model.gguf")
                .apply { writeBytes(byteArrayOf(1)) }

            assertNull(ModelFileNamePolicy.trustedPrivateImportSha256(external, privateModelsDir))
            assertNull(ModelFileNamePolicy.trustedPrivateImportSha256(malformed, privateModelsDir))
        } finally {
            root.deleteRecursively()
        }
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
