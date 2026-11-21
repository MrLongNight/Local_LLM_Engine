package com.example.ui.diagnostics

import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FlashAttentionMode
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup

/**
 * Fixed, short CPU/Vulkan regression comparison for the target-device baseline.
 * Both profiles share the exact model, prompt, context, batch, threads and
 * deterministic sampling; only backend-specific GPU offload differs.
 */
internal object InferenceConfigSmokePlan {
    internal data class D0ModelAssessment(
        val status: String,
        val reason: String,
        val shaStatus: String,
        val baselineEligible: Boolean
    )

    /**
     * D0 accepts the currently loaded model without checking its name, architecture,
     * parameter count or quantization. A valid SHA proves that all three reloads use
     * exactly the same model bytes.
     */
    internal fun assessD0ModelSha(model: ModelInfo): D0ModelAssessment {
        val sha = model.sha256?.trim()
        val shaStatus = when {
            sha.isNullOrEmpty() -> "UNAVAILABLE"
            sha.matches(Regex("[0-9a-fA-F]{64}")) -> "AVAILABLE"
            else -> "INVALID"
        }
        val status = when (shaStatus) {
            "AVAILABLE" -> "MODEL_SHA_AVAILABLE"
            "UNAVAILABLE" -> "MODEL_SHA_UNAVAILABLE"
            else -> "MODEL_SHA_INVALID"
        }
        val reason = when (shaStatus) {
            "AVAILABLE" -> "sha256_available"
            "UNAVAILABLE" -> "sha256_unavailable"
            else -> "sha256_invalid"
        }
        return D0ModelAssessment(status, reason, shaStatus, shaStatus == "AVAILABLE")
    }

    const val MEASURED_TOKENS = 96
    const val D0_CPU_REFERENCE_RUNS = 3
    const val COOLDOWN_MS = 0L

    const val USER_PROMPT =
        "Berechne 8 * 24 - 37. Gib genau zwei Zeilen aus: In Zeile 1 ERGEBNIS=<berechnetes Ergebnis>. " +
            "In Zeile 2 FOLGE=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32. " +
            "Keine Einleitung, keine Erklärung und kein weiterer Text."

    const val EXPECTED_OUTPUT =
        "ERGEBNIS=155\n" +
            "FOLGE=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32"

    fun normalizeOutput(output: String): String =
        output.replace("\r\n", "\n").replace('\r', '\n').trim()

    internal data class PairedGateSummary(
        val outputGateStatus: String,
        val backendParityStatus: String,
        val combinedStatus: String
    )

    /**
     * Keeps deterministic-output correctness separate from numeric CPU/Vulkan parity.
     * The parity evaluator runs whenever both backend samples exist, even if either
     * sample failed its exact-output/STOP gate.
     */
    internal fun evaluatePairedGate(
        cpuOutputGatePassed: Boolean?,
        vulkanOutputGatePassed: Boolean?,
        evaluateBackendParity: () -> String
    ): PairedGateSummary {
        val bothRunsAvailable = cpuOutputGatePassed != null && vulkanOutputGatePassed != null
        val outputGateStatus = when {
            cpuOutputGatePassed == false || vulkanOutputGatePassed == false -> "FAIL"
            bothRunsAvailable -> "PASS"
            else -> "UNAVAILABLE"
        }
        val backendParityStatus = if (bothRunsAvailable) {
            evaluateBackendParity().takeIf { it == "PASS" || it == "FAIL" } ?: "UNAVAILABLE"
        } else {
            "UNAVAILABLE"
        }
        val combinedStatus = when {
            outputGateStatus == "FAIL" || backendParityStatus == "FAIL" -> "FAIL"
            outputGateStatus == "PASS" && backendParityStatus == "PASS" -> "PASS"
            else -> "UNAVAILABLE"
        }
        return PairedGateSummary(outputGateStatus, backendParityStatus, combinedStatus)
    }

    /**
     * A stable service state permits exactly one next-profile model-load attempt.
     * Successful loadModel completion, not Binder connection alone, confirms recovery.
     */
    internal fun mayContinueAfterProfileFailure(
        stableStateConfirmed: Boolean,
        cancelled: Boolean = false,
        safetyStop: Boolean = false,
        modelIdentityMismatch: Boolean = false
    ): Boolean = stableStateConfirmed && !cancelled && !safetyStop && !modelIdentityMismatch

    internal data class Profile(
        val id: String,
        val label: String,
        val expectedBackend: String,
        val config: EngineConfig
    )

    /** Three independent CPU profiles; D0 deliberately never creates a Vulkan profile. */
    fun createD0CpuReferenceProfiles(
        base: EngineConfig,
        availableProcessors: Int
    ): List<Profile> {
        val common = fixedSmokeConfig(base, availableProcessors)
        return (1..D0_CPU_REFERENCE_RUNS).map { run ->
            Profile(
                id = "cpu-reference-$run",
                label = "CPU · Referenz $run/$D0_CPU_REFERENCE_RUNS",
                expectedBackend = "CPU",
                config = common.copy(backendPreference = BackendPreference.CPU, gpuLayers = 0)
            )
        }
    }

    /** Paired runner reserved for D1a and explicitly requested CPU/Vulkan comparisons. */
    fun create(base: EngineConfig, availableProcessors: Int): List<Profile> {
        val common = fixedSmokeConfig(base, availableProcessors)
        return listOf(
            Profile(
                id = "cpu-reference",
                label = "CPU · Referenz",
                expectedBackend = "CPU",
                config = common.copy(backendPreference = BackendPreference.CPU, gpuLayers = 0)
            ),
            Profile(
                id = "vulkan-candidate",
                label = "Vulkan · Kandidat",
                expectedBackend = "VULKAN",
                config = common.copy(backendPreference = BackendPreference.VULKAN, gpuLayers = 99)
            )
        )
    }

    private fun fixedSmokeConfig(base: EngineConfig, availableProcessors: Int): EngineConfig {
        val threadCount = availableProcessors.coerceAtLeast(1).coerceAtMost(6)
        return base.copy(
            contextSize = 256,
            batchSize = 128,
            threads = threadCount,
            batchThreads = threadCount,
            microBatchSize = 64,
            temperature = 0f,
            topP = 1f,
            topK = 20,
            minP = 0.05f,
            maxPredictTokens = MEASURED_TOKENS,
            vulkanMatvecWorkgroup = VulkanMatvecWorkgroup.AUTO,
            flashAttentionMode = FlashAttentionMode.AUTO,
            gdnPolicy = GdnPolicy.ARCH_DEFAULT,
            memoryGuardOverride = false
        )
    }

    /** A D0 reload must prove the exact imported model bytes again. */
    internal fun matchesD0ModelBytes(requested: ModelInfo, loaded: ModelInfo): Boolean {
        val requestedSha = requested.sha256?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: return false
        val loadedSha = loaded.sha256?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: return false
        return requested.sizeBytes > 0L &&
            requested.sizeBytes == loaded.sizeBytes &&
            requestedSha.equals(loadedSha, ignoreCase = true)
    }

    internal fun requiresQ4kDecodeEvidence(expectedBackend: String, expectsQ4kModel: Boolean): Boolean =
        expectedBackend.equals("VULKAN", ignoreCase = true) && expectsQ4kModel

    internal fun resolvedUbatchForTest(config: EngineConfig, activeBackend: String? = null): Int =
        if (config.microBatchSize > 0) minOf(config.microBatchSize, config.batchSize)
        else if (activeBackend?.contains("Vulkan", ignoreCase = true) == true ||
            (activeBackend == null && config.backendPreference != BackendPreference.CPU)
        ) minOf(64, config.batchSize) else config.batchSize
}
