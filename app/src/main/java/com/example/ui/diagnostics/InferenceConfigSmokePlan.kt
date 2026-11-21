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
     * D0 accepts the currently loaded model source. A missing SHA is normal for
     * direct SAF descriptors and must not block the CPU reference run.
     */
    internal fun assessD0ModelSource(model: ModelInfo): D0ModelAssessment {
        val sourceAvailable = model.path.isNotBlank() && model.sizeBytes > 0L
        val sha = model.sha256?.trim()
        val shaStatus = when {
            sha.isNullOrEmpty() -> "UNAVAILABLE"
            sha.matches(Regex("[0-9a-fA-F]{64}")) -> "AVAILABLE"
            else -> "INVALID"
        }
        val status = when {
            !sourceAvailable -> "MODEL_SOURCE_UNAVAILABLE"
            shaStatus == "AVAILABLE" -> "SOURCE_SIZE_AND_SHA_AVAILABLE"
            else -> "SOURCE_AND_SIZE_ONLY"
        }
        val reason = when {
            !sourceAvailable -> "model_source_or_size_unavailable"
            shaStatus == "AVAILABLE" -> "same_source_size_and_sha_available"
            else -> "same_source_and_size_sha_optional"
        }
        return D0ModelAssessment(
            status = status,
            reason = reason,
            shaStatus = shaStatus,
            baselineEligible = sourceAvailable
        )
    }

    const val MEASURED_TOKENS = 96
    const val D0_CPU_REFERENCE_RUNS = 3
    const val COOLDOWN_MS = 0L

    const val USER_PROMPT =
        "Berechne 8 * 24 - 37. Gib ausschließlich ERGEBNIS=<Zahl> aus."

    const val EXPECTED_OUTPUT = "ERGEBNIS=155"

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
        recommendedThreads: Int
    ): List<Profile> {
        val common = fixedSmokeConfig(base, recommendedThreads)
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
    fun create(base: EngineConfig, recommendedThreads: Int): List<Profile> {
        val common = fixedSmokeConfig(base, recommendedThreads)
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

    private fun fixedSmokeConfig(base: EngineConfig, recommendedThreads: Int): EngineConfig {
        val threadCount = recommendedThreads.coerceAtLeast(1).coerceAtMost(6)
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

    /**
     * Shared by D0 and the paired CPU/Vulkan runner. The caller reuses the same
     * ModelSource for each profile; this is the existing paired size/optional-SHA rule.
     */
    internal fun matchesReloadedModelIdentity(requested: ModelInfo, loaded: ModelInfo): Boolean =
        requested.sizeBytes == loaded.sizeBytes &&
            (requested.sha256.isNullOrBlank() ||
                requested.sha256.equals(loaded.sha256, ignoreCase = true))

    internal fun requiresQ4kDecodeEvidence(expectedBackend: String, expectsQ4kModel: Boolean): Boolean =
        expectedBackend.equals("VULKAN", ignoreCase = true) && expectsQ4kModel

    internal fun resolvedUbatchForTest(config: EngineConfig, activeBackend: String? = null): Int =
        if (config.microBatchSize > 0) minOf(config.microBatchSize, config.batchSize)
        else if (activeBackend?.contains("Vulkan", ignoreCase = true) == true ||
            (activeBackend == null && config.backendPreference != BackendPreference.CPU)
        ) minOf(64, config.batchSize) else config.batchSize
}
