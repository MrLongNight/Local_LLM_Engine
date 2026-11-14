package com.example.ui

import com.example.domain.InferenceConfigSmokePlan

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceConfigSmokePlanTest {

    @Test
    fun createsBaselineAndSingleParameterVariants() {
        val base = EngineConfig(
            contextSize = 2048,
            batchSize = 128,
            threads = 6,
            backendPreference = BackendPreference.VULKAN,
            gpuLayers = 37
        )
        val activeBackend = "Vulkan selected (Mali-G57)"
        val profiles = InferenceConfigSmokePlan.create(
            base, availableProcessors = 8, activeBackend = activeBackend
        )

        assertEquals("baseline", profiles.first().id)
        assertEquals(0, profiles.first().config.microBatchSize) // keep Vulkan AUTO
        assertEquals(64, profiles.first().config.let { InferenceConfigSmokePlan.resolvedUbatchForTest(it, activeBackend) })
        assertTrue(profiles.size >= 5)
        assertTrue(profiles.any { it.config.threads == 4 && it.config.batchThreads == 6 })
        assertTrue(profiles.any { it.config.threads == 8 && it.config.batchThreads == 6 })
        assertTrue(profiles.any { it.config.threads == 6 && it.config.batchThreads == 4 })
        assertTrue(profiles.any { it.config.threads == 6 && it.config.batchSize == 512 })
        assertTrue(profiles.any { it.config.threads == 6 && it.config.microBatchSize == 32 })

        val baseline = profiles.first().config
        profiles.drop(1).forEach { profile ->
            val changed = listOf(
                profile.config.threads != baseline.threads,
                profile.config.batchThreads != baseline.batchThreads,
                profile.config.batchSize != baseline.batchSize,
                InferenceConfigSmokePlan.resolvedUbatchForTest(profile.config, activeBackend) !=
                    InferenceConfigSmokePlan.resolvedUbatchForTest(baseline, activeBackend)
            ).count { it }
            assertEquals("Profile " + profile.id + " must change exactly one axis", 1, changed)
            assertEquals(baseline.contextSize, profile.config.contextSize)
            assertEquals(baseline.backendPreference, profile.config.backendPreference)
            assertEquals(baseline.gpuLayers, profile.config.gpuLayers)
            assertEquals(0f, profile.config.temperature, 0f)
            assertEquals(1f, profile.config.topP, 0f)
        }
    }


    @Test
    fun autoBaselineUsesTheActualCpuBackendNBatch() {
        val base = EngineConfig(
            batchSize = 128,
            threads = 4,
            backendPreference = BackendPreference.AUTO
        )
        val profiles = InferenceConfigSmokePlan.create(
            base,
            availableProcessors = 8,
            activeBackend = "CPU (registered ggml CPU backend)"
        )
        assertEquals(0, profiles.first().config.microBatchSize)
        assertEquals(
            128,
            InferenceConfigSmokePlan.resolvedUbatchForTest(
                profiles.first().config,
                "CPU (registered ggml CPU backend)"
            )
        )
        val batchProfile = profiles.firstOrNull { it.id == "batch-default" }
        if (batchProfile != null) {
            assertEquals(128, batchProfile.config.microBatchSize)
        }
    }

    @Test
    fun omitsNBatchCandidateWhenItWouldMakeMicroBatchInvalid() {
        val base = EngineConfig(batchSize = 1024, threads = 6, microBatchSize = 1024)
        val profiles = InferenceConfigSmokePlan.create(base, availableProcessors = 8)
        assertTrue(profiles.none { it.id == "batch-default" })
        profiles.forEach { profile ->
            val ubatch = InferenceConfigSmokePlan.resolvedUbatchForTest(profile.config)
            assertTrue(ubatch <= profile.config.batchSize)
        }
    }

    @Test
    fun removesProfilesThatDuplicateCurrentValues() {
        val base = EngineConfig(threads = 4, batchSize = 128)
        val profiles = InferenceConfigSmokePlan.create(base, availableProcessors = 4)
        val signatures = profiles.map {
            it.config.threads.toString() + ":" + it.config.batchThreads +
                ":" + it.config.batchSize + ":" +
                InferenceConfigSmokePlan.resolvedUbatchForTest(it.config)
        }
        assertEquals(signatures.size, signatures.distinct().size)
    }
}
