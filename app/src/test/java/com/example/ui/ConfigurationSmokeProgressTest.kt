package com.example.ui

import com.example.ui.diagnostics.ConfigurationSmokePhase
import com.example.ui.diagnostics.ConfigurationSmokeProgress
import com.example.ui.diagnostics.ConfigurationSmokeRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSmokeProgressTest {

    @Test
    fun stepProgressReflectsTheSevenVisibleTestPhasesAndStaysBounded() {
        val loading = ConfigurationSmokeProgress(
            status = ConfigurationSmokeRunStatus.RUNNING,
            phase = ConfigurationSmokePhase.CPU_MODEL_LOADING,
            message = "CPU-Modell wird geladen",
            currentStep = 2
        )
        assertEquals(2f / 7f, loading.stepProgress, 0.0001f)

        val completed = loading.copy(
            phase = ConfigurationSmokePhase.COMPLETE,
            status = ConfigurationSmokeRunStatus.PASSED,
            currentStep = 7
        )
        assertEquals(1f, completed.stepProgress, 0f)
        assertFalse(completed.isRunning)
    }

    @Test
    fun promptAndGenerationProgressUseOnlyReportedPositiveTotals() {
        val progress = ConfigurationSmokeProgress(
            status = ConfigurationSmokeRunStatus.RUNNING,
            phase = ConfigurationSmokePhase.CPU_INFERENCE,
            message = "Generierung läuft",
            currentStep = 3,
            promptTokensProcessed = 18,
            promptTokensTotal = 36,
            generatedTokens = 24,
            tokenLimit = 96
        )

        assertEquals(0.5f, progress.promptProgress!!, 0f)
        assertEquals(0.25f, progress.tokenProgress!!, 0f)
    }

    @Test
    fun progressDoesNotInventFractionsWhenNativeCountersAreMissing() {
        val progress = ConfigurationSmokeProgress(
            status = ConfigurationSmokeRunStatus.RUNNING,
            phase = ConfigurationSmokePhase.VULKAN_MODEL_LOADING,
            message = "Vulkan-Modell wird geladen",
            currentStep = 4
        )

        assertNull(progress.promptProgress)
        assertNull(progress.tokenProgress)
        assertTrue(progress.isRunning)
    }

    @Test
    fun countersAreClampedToTheirActualRanges() {
        val progress = ConfigurationSmokeProgress(
            status = ConfigurationSmokeRunStatus.RUNNING,
            phase = ConfigurationSmokePhase.CPU_INFERENCE,
            message = "Generierung läuft",
            currentStep = 99,
            promptTokensProcessed = 50,
            promptTokensTotal = 40,
            generatedTokens = 120,
            tokenLimit = 96
        )

        assertEquals(1f, progress.stepProgress, 0f)
        assertEquals(1f, progress.promptProgress!!, 0f)
        assertEquals(1f, progress.tokenProgress!!, 0f)
    }
}
