package com.mydocanizer.llm.engine.internal

import java.util.Locale

/**
 * Fail-closed comparison of a CPU reference run and a Vulkan candidate run.
 * The request contract is checked before token-selection logits are compared.
 */
internal object BackendCorrectnessGate {
    data class Evaluation(
        val status: String,
        val reason: String,
        val comparedSteps: Int = 0,
        val minimumTop5Overlap: Int = 0,
        val firstDivergenceStep: Int? = null
    ) {
        val passed: Boolean get() = status == "PASS"
    }

    private data class TokenTrace(val selectedId: Int, val top: List<Pair<Int, Float>>)

    private const val TOKEN_TRACE_MARKER = "TOKEN_SELECTION_TRACE - "
    private const val TEXT_TRACE_MARKER = "LOCAL_LLM_TEXT_TRACE - "
    private const val PROMPT_METADATA_MARKER = "LOCAL_LLM_PROMPT_METADATA - "
    private const val SAMPLER_MARKER = "LOCAL_LLM_SAMPLER_PARAMETERS - "
    private const val RUN_CONTEXT_MARKER = "INFERENCE_RUN_CONTEXT - "
    private const val CACHE_PLAN_MARKER = "Prompt cache plan:"

    private val comparableRunFields = listOf(
        "modelBytes", "architecture",
        "contextSize", "batchSize", "microBatchRequested", "microBatchEffective",
        "threads", "batchThreads", "flashAttention",
        "temperatureDefault", "temperatureOverride",
        "topPDefault", "topPOverride", "topK", "minP",
        "maxPredictTokens", "enableThinking", "messageCount",
        "inputChars", "timeout"
    )
    private val comparablePromptMetadataFields = listOf(
        "architecture", "modelName", "tokenizerModel",
        "modelHybrid", "modelRecurrent", "thinkingEnabled",
        "explicitChatTemplate", "templateMode", "usedChatTemplate",
        "messageCount", "vocabSize", "addBOS", "bosId", "addEOS", "eosId"
    )

    fun evaluate(lines: List<String>, cpuRunId: String, candidateRunId: String): Evaluation {
        if (cpuRunId.isBlank() || candidateRunId.isBlank() || cpuRunId == candidateRunId) {
            return unavailable("RUN_IDS_MISSING_OR_NOT_DISTINCT")
        }
        val cpuContext = singleFields(lines, RUN_CONTEXT_MARKER, cpuRunId)
            ?: return unavailable("CPU_RUN_CONTEXT_MISSING_OR_DUPLICATED")
        val candidateContext = singleFields(lines, RUN_CONTEXT_MARKER, candidateRunId)
            ?: return unavailable("VULKAN_RUN_CONTEXT_MISSING_OR_DUPLICATED")
        if (!cpuContext.required("activeBackend").contains("CPU", ignoreCase = true)) {
            return fail("CPU_REFERENCE_BACKEND_NOT_CONFIRMED")
        }
        if (!candidateContext.required("activeBackend").contains("Vulkan", ignoreCase = true)) {
            return fail("VULKAN_CANDIDATE_BACKEND_NOT_CONFIRMED")
        }

        val missingRunFields = comparableRunFields.filter {
            cpuContext[it].isNullOrBlank() || candidateContext[it].isNullOrBlank()
        }
        if (missingRunFields.isNotEmpty()) {
            return unavailable("RUN_CONFIGURATION_EVIDENCE_MISSING:" + missingRunFields.joinToString("|"))
        }
        val sha256Pattern = Regex("[0-9a-fA-F]{64}")
        val cpuSha256 = cpuContext["modelSha256"].orEmpty()
        val candidateSha256 = candidateContext["modelSha256"].orEmpty()
        if (!sha256Pattern.matches(cpuSha256) || !sha256Pattern.matches(candidateSha256)) {
            return unavailable("MODEL_SHA256_UNAVAILABLE_OR_INVALID")
        }
        if (!cpuSha256.equals(candidateSha256, ignoreCase = true)) {
            return fail("MODEL_CONTENT_SHA256_MISMATCH")
        }

        val runMismatches = comparableRunFields.filter { cpuContext[it] != candidateContext[it] }
        if (runMismatches.isNotEmpty()) {
            return fail("MODEL_OR_INFERENCE_CONFIGURATION_MISMATCH:" + runMismatches.joinToString("|"))
        }

        val cpuPromptMetadata = singleFields(lines, PROMPT_METADATA_MARKER, cpuRunId)
            ?: return unavailable("CPU_PROMPT_METADATA_MISSING_OR_DUPLICATED")
        val candidatePromptMetadata = singleFields(lines, PROMPT_METADATA_MARKER, candidateRunId)
            ?: return unavailable("VULKAN_PROMPT_METADATA_MISSING_OR_DUPLICATED")
        val missingMetadata = comparablePromptMetadataFields.filter {
            cpuPromptMetadata[it].isNullOrBlank() || candidatePromptMetadata[it].isNullOrBlank()
        }
        if (missingMetadata.isNotEmpty()) {
            return unavailable("PROMPT_METADATA_FIELDS_MISSING:" + missingMetadata.joinToString("|"))
        }
        val metadataMismatches = comparablePromptMetadataFields.filter {
            cpuPromptMetadata[it] != candidatePromptMetadata[it]
        }
        if (metadataMismatches.isNotEmpty()) {
            val templateFields = setOf(
                "modelName", "architecture", "explicitChatTemplate", "templateMode", "usedChatTemplate"
            )
            val tokenizerFields = setOf("tokenizerModel", "vocabSize", "addBOS", "bosId", "addEOS", "eosId")
            val reason = when {
                metadataMismatches.any { it in templateFields } -> "CHAT_TEMPLATE_METADATA_MISMATCH"
                metadataMismatches.any { it in tokenizerFields } -> "TOKENIZER_METADATA_MISMATCH"
                else -> "MODEL_ARCHITECTURE_OR_STATE_METADATA_MISMATCH"
            }
            return fail(reason + ":" + metadataMismatches.joinToString("|"))
        }

        for (kind in listOf("INPUT_MESSAGES", "RENDERED_PROMPT")) {
            val cpuText = textTrace(lines, cpuRunId, kind)
                ?: return unavailable("CPU_" + kind + "_TRACE_MISSING_OR_INCOMPLETE")
            val candidateText = textTrace(lines, candidateRunId, kind)
                ?: return unavailable("VULKAN_" + kind + "_TRACE_MISSING_OR_INCOMPLETE")
            if (cpuText != candidateText) {
                return fail(if (kind == "RENDERED_PROMPT") {
                    "CHAT_TEMPLATE_OR_RENDERED_PROMPT_MISMATCH"
                } else {
                    "INPUT_MESSAGE_MISMATCH"
                })
            }
        }

        val cpuPromptTokens = textTrace(lines, cpuRunId, "PROMPT_TOKEN_IDS")
            ?: return unavailable("CPU_PROMPT_TOKEN_IDS_MISSING_OR_INCOMPLETE")
        val candidatePromptTokens = textTrace(lines, candidateRunId, "PROMPT_TOKEN_IDS")
            ?: return unavailable("VULKAN_PROMPT_TOKEN_IDS_MISSING_OR_INCOMPLETE")
        val cpuTokenIds = parseTokenIds(cpuPromptTokens) ?: return fail("CPU_PROMPT_TOKEN_IDS_MALFORMED")
        val candidateTokenIds = parseTokenIds(candidatePromptTokens)
            ?: return fail("VULKAN_PROMPT_TOKEN_IDS_MALFORMED")
        if (cpuTokenIds != candidateTokenIds) return fail("TOKENIZER_TOKEN_ID_MISMATCH")

        val cpuSampler = singleFields(lines, SAMPLER_MARKER, cpuRunId)
            ?: return unavailable("CPU_SAMPLER_PARAMETERS_MISSING_OR_DUPLICATED")
        val candidateSampler = singleFields(lines, SAMPLER_MARKER, candidateRunId)
            ?: return unavailable("VULKAN_SAMPLER_PARAMETERS_MISSING_OR_DUPLICATED")
        if ((cpuSampler - "runId") != (candidateSampler - "runId")) {
            return fail("SAMPLER_CONFIGURATION_MISMATCH")
        }
        val temperature = cpuSampler["temperature"]?.toFloatOrNull()
        if (temperature == null || !temperature.isFinite() || temperature > 0f ||
            cpuSampler["samplerChain"]?.contains("GREEDY", ignoreCase = true) != true
        ) return fail("CORRECTNESS_GATE_REQUIRES_GREEDY_SAMPLING")

        val cpuCache = singleFields(lines, CACHE_PLAN_MARKER, cpuRunId)
            ?: return unavailable("CPU_PROMPT_CACHE_EVIDENCE_MISSING_OR_DUPLICATED")
        val candidateCache = singleFields(lines, CACHE_PLAN_MARKER, candidateRunId)
            ?: return unavailable("VULKAN_PROMPT_CACHE_EVIDENCE_MISSING_OR_DUPLICATED")
        for ((backend, cache) in listOf("CPU" to cpuCache, "Vulkan" to candidateCache)) {
            val promptTokens = cache["promptTokens"]?.toIntOrNull()
            val reusedTokens = cache["reusedTokens"]?.toIntOrNull()
            val evaluatedTokens = cache["evaluatedTokens"]?.toIntOrNull()
            if (promptTokens == null || reusedTokens == null || evaluatedTokens == null ||
                promptTokens <= 0 || evaluatedTokens < 0
            ) return unavailable(backend + "_PROMPT_CACHE_EVIDENCE_MALFORMED")
            if (reusedTokens != 0 || evaluatedTokens != promptTokens) {
                return fail(backend.uppercase(Locale.US) + "_PROMPT_CACHE_NOT_FRESH")
            }
        }

        val cpuTraces = parseTokenTraces(lines, cpuRunId, "CPU")
            ?: return unavailable("CPU_TOKEN_SELECTION_TRACE_MISSING_OR_MALFORMED")
        val candidateTraces = parseTokenTraces(lines, candidateRunId, "Vulkan")
            ?: return unavailable("VULKAN_TOKEN_SELECTION_TRACE_MISSING_OR_MALFORMED")
        if (cpuTraces.keys != candidateTraces.keys) return fail("CPU_VULKAN_TRACE_STEP_MISMATCH")
        if (cpuTraces.size < 4) return unavailable("INSUFFICIENT_COMPARABLE_TOKEN_STEPS:" + cpuTraces.size)

        var minimumOverlap = 5
        val firstStep = cpuTraces.keys.minOrNull()
        for (step in cpuTraces.keys.sorted()) {
            val cpu = cpuTraces.getValue(step)
            val candidate = candidateTraces.getValue(step)
            val overlap = cpu.top.map { it.first }.toSet()
                .intersect(candidate.top.map { it.first }.toSet()).size
            minimumOverlap = minOf(minimumOverlap, overlap)
            if (cpu.selectedId != candidate.selectedId) {
                return fail(
                    if (overlap >= 4) "NUMERICALLY_PLAUSIBLE_TOP1_MISMATCH"
                    else if (step == firstStep) "INITIAL_LOGIT_DISTRIBUTION_DIVERGENCE"
                    else "DECODE_STATE_OR_LOGIT_DIVERGENCE",
                    cpuTraces.size, minimumOverlap, step
                )
            }
            if (overlap < 4) {
                return fail(
                    if (step == firstStep) "INITIAL_LOGIT_DISTRIBUTION_DIVERGENCE"
                    else "DECODE_STATE_OR_LOGIT_DIVERGENCE",
                    cpuTraces.size, minimumOverlap, step
                )
            }
        }

        return Evaluation(
            "PASS", "TOP1_IDENTICAL_TOP5_OVERLAP_GE_4",
            cpuTraces.size, minimumOverlap
        )
    }

    private fun singleFields(lines: List<String>, marker: String, runId: String): Map<String, String>? {
        val matches = lines.asSequence()
            .filter { it.contains(marker) }
            .mapNotNull { line ->
                val payload = line.substringAfter(marker, missingDelimiterValue = "")
                if (payload.isBlank()) null else parseFields(payload)
            }
            .filter { it["runId"] == runId }
            .toList()
        return matches.singleOrNull()
    }

    private fun parseFields(payload: String): Map<String, String> =
        payload.substringBefore(",text=").split(',')
            .mapNotNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) null
                else item.substring(0, separator).trim() to item.substring(separator + 1).trim()
            }.toMap()

    private fun Map<String, String>.required(key: String): String = this[key].orEmpty()

    private fun textTrace(lines: List<String>, runId: String, kind: String): String? {
        val entries = lines.asSequence()
            .filter { it.contains(TEXT_TRACE_MARKER) }
            .mapNotNull { line ->
                val payload = line.substringAfter(TEXT_TRACE_MARKER, missingDelimiterValue = "")
                if (payload.isBlank()) return@mapNotNull null
                val fields = parseFields(payload)
                if (fields["runId"] != runId || fields["kind"] != kind) return@mapNotNull null
                val textIndex = payload.indexOf(",text=")
                if (textIndex < 0) return@mapNotNull null
                val part = fields["part"] ?: return@mapNotNull null
                val index = part.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                val total = part.substringAfter('/', "").toIntOrNull() ?: return@mapNotNull null
                if (index <= 0 || total <= 0 || index > total) return@mapNotNull null
                Triple(index, total, payload.substring(textIndex + ",text=".length))
            }.toList()
        if (entries.isEmpty()) return null
        val totals = entries.map { it.second }.distinct()
        if (totals.size != 1) return null
        val total = totals.single()
        if (entries.map { it.first }.distinct().size != total ||
            entries.map { it.first }.toSet() != (1..total).toSet()
        ) return null
        return entries.sortedBy { it.first }.joinToString(separator = "") { it.third }
    }

    private fun parseTokenIds(text: String): List<Int>? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun parseTokenTraces(
        lines: List<String>,
        runId: String,
        expectedBackend: String
    ): Map<Int, TokenTrace>? {
        val traces = linkedMapOf<Int, TokenTrace>()
        for (line in lines.filter { it.contains(TOKEN_TRACE_MARKER) }) {
            val payload = line.substringAfter(TOKEN_TRACE_MARKER, missingDelimiterValue = "")
            val fields = parseFields(payload)
            if (fields["runId"] != runId) continue
            if (fields["backend"] != expectedBackend) return null
            val step = fields["step"]?.toIntOrNull() ?: return null
            val selected = fields["selectedId"]?.toIntOrNull() ?: return null
            val topText = fields["top5"] ?: return null
            val top = topText.split('|').map { entry ->
                val separator = entry.indexOf(':')
                if (separator <= 0) return null
                val token = entry.substring(0, separator).toIntOrNull() ?: return null
                val value = entry.substring(separator + 1).toFloatOrNull() ?: return null
                if (!value.isFinite()) return null
                token to value
            }
            if (step <= 0 || top.size != 5 || top.map { it.first }.distinct().size != 5) return null
            if (top.zipWithNext().any { (previous, next) -> previous.second < next.second }) return null
            if (selected != top.first().first) return null
            if (traces.put(step, TokenTrace(selected, top)) != null) return null
        }
        if (traces.isEmpty()) return null
        return traces.toSortedMap()
    }

    private fun unavailable(reason: String) = Evaluation("UNAVAILABLE", reason)
    private fun fail(
        reason: String,
        comparedSteps: Int = 0,
        minimumTop5Overlap: Int = 0,
        firstDivergenceStep: Int? = null
    ) = Evaluation("FAIL", reason, comparedSteps, minimumTop5Overlap, firstDivergenceStep)
}
