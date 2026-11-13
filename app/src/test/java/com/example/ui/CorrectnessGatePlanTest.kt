package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectnessGatePlanTest {
    @Test
    fun acceptsCpuReferenceWithExpectedContinuationAndSixteenValidGreedySteps() {
        val run = CorrectnessGatePlan.parse(expectedOutput, trace("cpu-run", "CPU"))
        val result = CorrectnessGatePlan.evaluate(run, expectedBackend = "CPU")

        assertTrue(result.passed)
        assertEquals(16, result.comparedSteps)
        assertEquals(5, result.minimumTop5Overlap)
        assertTrue(result.maxConsecutiveRepeat <= 8)
    }

    @Test
    fun acceptsVulkanOnlyWhenTop1MatchesCpuAndEveryTopFiveOverlapsByAtLeastFour() {
        val cpu = CorrectnessGatePlan.parse(expectedOutput, trace("cpu-run", "CPU"))
        val vulkan = CorrectnessGatePlan.parse(expectedOutput, trace("vulkan-run", "Vulkan", replaceFifth = true))
        val result = CorrectnessGatePlan.evaluate(vulkan, cpu, expectedBackend = "Vulkan")

        assertTrue(result.passed)
        assertEquals(4, result.minimumTop5Overlap)
        assertEquals("TOP1_IDENTICAL_TOP5_OVERLAP_GE_4", result.reason)
    }

    @Test
    fun rejectsVulkanWhenTop1DiffersFromCpu() {
        val cpu = CorrectnessGatePlan.parse(expectedOutput, trace("cpu-run", "CPU"))
        val vulkan = CorrectnessGatePlan.parse(
            expectedOutput,
            trace("vulkan-run", "Vulkan", changeSelectedAtStep = 9)
        )
        val result = CorrectnessGatePlan.evaluate(vulkan, cpu, expectedBackend = "Vulkan")

        assertFalse(result.passed)
        assertEquals("CPU_TOP1_MISMATCH", result.reason)
    }

    @Test
    fun rejectsVulkanWhenTopFiveOverlapIsBelowFour() {
        val cpu = CorrectnessGatePlan.parse(expectedOutput, trace("cpu-run", "CPU"))
        val vulkan = CorrectnessGatePlan.parse(
            expectedOutput,
            trace("vulkan-run", "Vulkan", overlapOnlyTop1 = true)
        )
        val result = CorrectnessGatePlan.evaluate(vulkan, cpu, expectedBackend = "Vulkan")

        assertFalse(result.passed)
        assertEquals("CPU_TOP5_OVERLAP_BELOW_4", result.reason)
        assertEquals(1, result.minimumTop5Overlap)
    }

    @Test
    fun rejectsRepeatedTokenRunLongerThanEight() {
        val lines = (1..16).map { step ->
            "2026-11-13 12:00:00.000 [INFO] [inference] TOKEN_SELECTION_TRACE - " +
                "runId=repeat-run,backend=CPU,step=$step,selectedId=77," +
                "top5=77:10.0|78:9.0|79:8.0|80:7.0|81:6.0"
        }
        val run = CorrectnessGatePlan.parse(expectedOutput, lines)
        val result = CorrectnessGatePlan.evaluate(run, expectedBackend = "CPU")

        assertFalse(result.passed)
        assertEquals("TOKEN_REPEAT_OVER_8", result.reason)
        assertEquals(16, result.maxConsecutiveRepeat)
    }

    @Test
    fun rejectsWrongExpectedAnswerAndIncompleteTrace() {
        val wrongOutput = CorrectnessGatePlan.parse("322, 324, 325, 328, 330", trace("wrong", "CPU"))
        val wrongResult = CorrectnessGatePlan.evaluate(wrongOutput, expectedBackend = "CPU")
        assertFalse(wrongResult.passed)
        assertEquals("EXPECTED_OUTPUT_MISMATCH", wrongResult.reason)

        val incomplete = CorrectnessGatePlan.parse(expectedOutput, trace("short", "CPU").take(15))
        val incompleteResult = CorrectnessGatePlan.evaluate(incomplete, expectedBackend = "CPU")
        assertFalse(incompleteResult.passed)
        assertEquals("TRACE_INCOMPLETE_15_OF_16", incompleteResult.reason)
    }

    private fun trace(
        runId: String,
        backend: String,
        replaceFifth: Boolean = false,
        changeSelectedAtStep: Int? = null,
        overlapOnlyTop1: Boolean = false
    ): List<String> = (1..16).map { step ->
        val selected = 100 + step
        val ids = when {
            overlapOnlyTop1 -> listOf(selected, 3000 + step, 4000 + step, 5000 + step, 6000 + step)
            replaceFifth -> listOf(selected, selected + 1, selected + 2, selected + 3, 9000 + step)
            else -> (selected..selected + 4).toList()
        }
        val chosen = if (changeSelectedAtStep == step) selected + 50 else selected
        val candidateIds = if (changeSelectedAtStep == step) {
            listOf(chosen, selected + 1, selected + 2, selected + 3, selected + 4)
        } else ids
        val top5 = candidateIds.mapIndexed { index, id -> "$id:${10.0 - index}" }.joinToString("|")
        "2026-11-13 12:00:00.000 [INFO] [inference] TOKEN_SELECTION_TRACE - " +
            "runId=$runId,backend=$backend,step=$step,selectedId=$chosen,top5=$top5"
    }

    private companion object {
        const val expectedOutput = "322, 324, 326, 328, 330"
    }
    @Test
    fun rejectsVulkanWhenCpuReferenceIsMissing() {
        val vulkan = CorrectnessGatePlan.parse(expectedOutput, trace("vulkan-run", "Vulkan"))
        val result = CorrectnessGatePlan.evaluate(vulkan, expectedBackend = "Vulkan")

        assertFalse(result.passed)
        assertEquals("CPU_REFERENCE_MISSING", result.reason)
    }

    @Test
    fun rejectsAdditionalNumbersOrExplanatoryTextAfterExpectedFiveNumbers() {
        val extraNumber = CorrectnessGatePlan.parse(
            "322, 324, 326, 328, 330, 332",
            trace("extra", "CPU")
        )
        val extraResult = CorrectnessGatePlan.evaluate(extraNumber, expectedBackend = "CPU")
        assertFalse(extraResult.passed)
        assertEquals("EXPECTED_OUTPUT_MISMATCH", extraResult.reason)

        val explanation = CorrectnessGatePlan.parse(
            "Die Antwort lautet: 322, 324, 326, 328, 330",
            trace("explanation", "CPU")
        )
        val explanationResult = CorrectnessGatePlan.evaluate(explanation, expectedBackend = "CPU")
        assertFalse(explanationResult.passed)
        assertEquals("EXPECTED_OUTPUT_MISMATCH", explanationResult.reason)
    }

}
