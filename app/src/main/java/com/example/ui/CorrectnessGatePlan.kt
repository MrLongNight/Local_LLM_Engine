package com.example.ui

/**
 * Pure evaluator for the Debug/TEXT_DIAGNOSTICS greedy correctness gate.
 * Trace rows contain token IDs and logits only; prompt and token-piece text is not parsed.
 */
internal object CorrectnessGatePlan {
    data class Candidate(val tokenId: Int, val logit: Double)
    data class TokenStep(val step: Int, val selectedId: Int, val top5: List<Candidate>)
    data class GateRun(
        val output: String,
        val runId: String,
        val backend: String,
        val steps: List<TokenStep>
    )

    enum class Status { PASS, FAIL }

    data class Evaluation(
        val status: Status,
        val reason: String,
        val comparedSteps: Int,
        val minimumTop5Overlap: Int,
        val maxConsecutiveRepeat: Int
    ) {
        val passed: Boolean get() = status == Status.PASS
    }

    private val numberPattern = Regex("""\d+""")

    fun parse(output: String, traceLines: List<String>): GateRun {
        val steps = traceLines.mapNotNull(::parseTraceLine).sortedBy { it.step }
        val payloads = traceLines.map { it.substringAfter("TOKEN_SELECTION_TRACE - ", "") }
        val runId = payloads.firstNotNullOfOrNull { field(it, "runId") }.orEmpty()
        val backend = payloads.firstNotNullOfOrNull { field(it, "backend") }.orEmpty()
        return GateRun(output, runId, backend, steps)
    }

    fun evaluate(
        candidate: GateRun,
        cpuReference: GateRun? = null,
        expectedBackend: String
    ): Evaluation {
        val localFailure = validateRun(candidate, expectedBackend)
        if (localFailure != null) return fail(localFailure, candidate)
        if (expectedBackend.contains("VULKAN", ignoreCase = true) && cpuReference == null) {
            return fail("CPU_REFERENCE_MISSING", candidate)
        }

        if (cpuReference != null) {
            val referenceFailure = validateRun(cpuReference, "CPU")
            if (referenceFailure != null) return fail("CPU_REFERENCE_$referenceFailure", candidate)

            val candidateSteps = candidate.steps.take(BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS)
            val referenceSteps = cpuReference.steps.take(BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS)
            val overlaps = candidateSteps.zip(referenceSteps).map { (actual, reference) ->
                actual.top5.map { it.tokenId }.toSet()
                    .intersect(reference.top5.map { it.tokenId }.toSet()).size
            }
            val minimumOverlap = overlaps.minOrNull() ?: 0
            if (candidateSteps.zip(referenceSteps).any { (actual, reference) ->
                    actual.selectedId != reference.selectedId
                }
            ) {
                return Evaluation(
                    Status.FAIL, "CPU_TOP1_MISMATCH",
                    BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS, minimumOverlap,
                    maxConsecutiveRepeat(candidateSteps)
                )
            }
            if (minimumOverlap < 4) {
                return Evaluation(
                    Status.FAIL, "CPU_TOP5_OVERLAP_BELOW_4",
                    BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS, minimumOverlap,
                    maxConsecutiveRepeat(candidateSteps)
                )
            }
            return Evaluation(
                Status.PASS, "TOP1_IDENTICAL_TOP5_OVERLAP_GE_4",
                BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS, minimumOverlap,
                maxConsecutiveRepeat(candidateSteps)
            )
        }

        return Evaluation(
            Status.PASS, "EXPECTED_OUTPUT_AND_LOCAL_TRACE_VALID",
            BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS, 5,
            maxConsecutiveRepeat(candidate.steps.take(BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS))
        )
    }

    private fun validateRun(run: GateRun, expectedBackend: String): String? {
        if (run.runId.isBlank()) return "RUN_ID_MISSING"
        if (run.backend.isBlank()) return "BACKEND_MISSING"
        if (!run.backend.contains(expectedBackend, ignoreCase = true)) {
            return "BACKEND_MISMATCH_EXPECTED_$expectedBackend"
        }
        val requiredSteps = BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS
        if (run.steps.size < requiredSteps) return "TRACE_INCOMPLETE_${run.steps.size}_OF_$requiredSteps"
        val steps = run.steps.take(requiredSteps)
        if (steps.map { it.step } != (1..requiredSteps).toList()) return "TRACE_STEP_SEQUENCE_INVALID"
        if (steps.any { it.top5.size != 5 || it.top5.map(Candidate::tokenId).distinct().size != 5 }) {
            return "TOP5_INCOMPLETE"
        }
        if (steps.any { it.selectedId != it.top5.first().tokenId }) return "SELECTED_TOKEN_NOT_TOP1"

        val actualNumbers = numberPattern.findAll(run.output).take(5).map { it.value }.toList()
        if (actualNumbers != BackendBenchmarkPlan.CORRECTNESS_GATE_EXPECTED) {
            return "EXPECTED_OUTPUT_MISMATCH"
        }
        if (maxConsecutiveRepeat(steps) > 8) return "TOKEN_REPEAT_OVER_8"
        return null
    }

    private fun maxConsecutiveRepeat(steps: List<TokenStep>): Int {
        var previous: Int? = null
        var current = 0
        var maximum = 0
        for (step in steps) {
            if (step.selectedId == previous) {
                current++
            } else {
                previous = step.selectedId
                current = 1
            }
            maximum = maxOf(maximum, current)
        }
        return maximum
    }

    private fun parseTraceLine(line: String): TokenStep? {
        val payload = line.substringAfter("TOKEN_SELECTION_TRACE - ", "")
        if (payload.isBlank()) return null
        val step = field(payload, "step")?.toIntOrNull() ?: return null
        val selectedId = field(payload, "selectedId")?.toIntOrNull() ?: return null
        val top5 = field(payload, "top5") ?: return null
        val candidates = top5.split('|').map { item ->
            val tokenId = item.substringBefore(':').toIntOrNull() ?: return null
            val logit = item.substringAfter(':', "").toDoubleOrNull() ?: return null
            Candidate(tokenId, logit)
        }
        return TokenStep(step, selectedId, candidates)
    }

    private fun field(payload: String, key: String): String? {
        val marker = "$key="
        val start = payload.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = payload.indexOf(',', valueStart).let { if (it < 0) payload.length else it }
        return payload.substring(valueStart, end).takeIf { it.isNotBlank() }
    }

    private fun fail(reason: String, candidate: GateRun): Evaluation =
        Evaluation(
            Status.FAIL,
            reason,
            candidate.steps.size.coerceAtMost(BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS),
            0,
            maxConsecutiveRepeat(candidate.steps.take(BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS))
        )
}
