package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mydocanizer.llm.engine.internal.BackendCorrectnessGate

/**
 * Diagnostic logger for Local LLM Engine lifecycle and inference. Generated assistant
 * responses are persisted with their inference run ID; prompts are recorded only when
 * an explicit test harness calls testText().
 */
object EngineDiagnostics {
    private const val TAG = "EngineDiagnostics"

    /** CPU/Vulkan comparison result. UNAVAILABLE is fail-closed and is never a pass. */
    data class BackendCorrectnessResult(
        val status: String,
        val reason: String,
        val comparedSteps: Int = 0,
        val minimumTop5Overlap: Int = 0,
        val firstDivergenceStep: Int? = null
    ) {
        val passed: Boolean get() = status == "PASS"
    }


    private const val TEXT_EVENT_CHUNK_CHARS = 2_200
    private const val MAX_TEXT_PAYLOAD_CHARS = 64 * 1024

    /**
     * Each process writes a separate file in shared app-private storage. This makes
     * :inference events exportable from the main process even after a service crash.
     */
    fun initialize(context: Context) = EngineDiagnosticsStorage.initialize(context)

    fun info(tag: String, event: String, details: String = "") =
        EngineDiagnosticsStorage.info(tag, event, details)

    /**
     * Persists text explicitly supplied by a test harness. Callers use this only for
     * deterministic test input/expected/actual values, never for ordinary chat prompts.
     */
    fun testText(tag: String, event: String, details: String) {
        if (!BuildConfig.DEBUG || !BuildConfig.TEXT_DIAGNOSTICS_ENABLED) return
        recordTextChunks(tag, event, "", details)
    }

    /**
     * Persists the complete generated assistant response (or partial response on
     * cancellation/error) only in explicitly enabled Debug text-diagnostics builds.
     * Release builds and Debug builds with text diagnostics disabled retain no raw output.
     */
    internal fun recordInferenceOutput(
        runId: String,
        text: String,
        finishReason: String,
        tokensGenerated: Int
    ) {
        if (!BuildConfig.DEBUG || !BuildConfig.TEXT_DIAGNOSTICS_ENABLED) return
        val metadata = "runId=$runId,finishReason=$finishReason,tokensGenerated=$tokensGenerated"
        recordTextChunks("inference", "INFERENCE_OUTPUT", metadata, text)
    }

    private fun recordTextChunks(tag: String, event: String, metadata: String, rawText: String) {
        val maxChars = MAX_TEXT_PAYLOAD_CHARS
        val truncated = rawText.length > maxChars
        var end = minOf(rawText.length, maxChars)
        if (end > 0 && end < rawText.length &&
            Character.isHighSurrogate(rawText[end - 1]) && Character.isLowSurrogate(rawText[end])) {
            end--
        }
        val boundedText = rawText.substring(0, end)
        val text = if (truncated) {
            boundedText + "\n...[text truncated at $MAX_TEXT_PAYLOAD_CHARS UTF-16 characters]"
        } else boundedText
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var chunkEnd = minOf(offset + TEXT_EVENT_CHUNK_CHARS, text.length)
            if (chunkEnd < text.length &&
                Character.isHighSurrogate(text[chunkEnd - 1]) && Character.isLowSurrogate(text[chunkEnd])) {
                chunkEnd--
            }
            chunks += text.substring(offset, chunkEnd)
            offset = chunkEnd
        }
        if (chunks.isEmpty()) chunks += ""
        chunks.forEachIndexed { index, chunk ->
            val partEvent = if (chunks.size == 1) event else "${event}_PART_${index + 1}_OF_${chunks.size}"
            val details = buildString {
                if (metadata.isNotBlank()) append(metadata).append(',')
                append("part=${index + 1}/${chunks.size},text=").append(chunk)
            }
            info(tag, partEvent, details)
        }
    }

    /**
     * Persist the numeric token-ID/logit row for one inference step. The raw token
     * piece is recorded separately by the native text-trace path.
     */
    internal fun recordTokenSelectionTrace(
        nativeRunId: String,
        backend: String,
        numericTrace: String
    ) {
        if (!BuildConfig.DEBUG || !BuildConfig.TEXT_DIAGNOSTICS_ENABLED) return
        if (!nativeRunId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return
        val normalizedBackend = when {
            backend.contains("Vulkan", ignoreCase = true) -> "Vulkan"
            backend.contains("CPU", ignoreCase = true) -> "CPU"
            else -> return
        }
        val numericTracePattern = Regex(
            """step=[0-9]+,selectedId=-?[0-9]+,top5=(?:-?[0-9]+:-?[0-9]+(?:\.[0-9]+)?(?:\|-?[0-9]+:-?[0-9]+(?:\.[0-9]+)?)*)?"""
        )
        if (!numericTracePattern.matches(numericTrace)) return
        info(
            "inference",
            "TOKEN_SELECTION_TRACE",
            "runId=$nativeRunId,backend=$normalizedBackend,$numericTrace"
        )
    }

    fun error(tag: String, event: String, throwable: Throwable? = null) =
        EngineDiagnosticsStorage.error(tag, event, throwable)



    fun getLogs(): List<String> = EngineDiagnosticsStorage.getLogs()

    /**
     * Reads only the structured, debug-gated token-ID/logit rows for one native inference run.
     * The native run ID is distinct from the Binder request ID used for RAM phase correlation.
     * Prompt and token-piece text is deliberately not returned by this helper.
     */
    fun tokenSelectionTraceLines(context: Context, nativeRunId: String): List<String> {
        if (!BuildConfig.DEBUG || !BuildConfig.TEXT_DIAGNOSTICS_ENABLED || nativeRunId.isBlank()) return emptyList()
        val marker = "TOKEN_SELECTION_TRACE - "
        return EngineDiagnosticsStorage.readAllProcessLogs(context).filter { line ->
            if (!line.contains(marker)) return@filter false
            line.substringAfter(marker).startsWith("runId=$nativeRunId,")
        }
    }

    /**
     * Compares one CPU reference with one Vulkan candidate using run-correlated Debug
     * evidence. Missing prompt, tokenizer, sampler, cache or numeric evidence fails closed.
     * Release builds intentionally return UNAVAILABLE.
     */
    fun evaluateCpuVulkanCorrectnessGate(
        context: Context,
        cpuNativeRunId: String,
        vulkanNativeRunId: String
    ): BackendCorrectnessResult {
        if (!BuildConfig.DEBUG || !BuildConfig.TEXT_DIAGNOSTICS_ENABLED) {
            return BackendCorrectnessResult("UNAVAILABLE", "TEXT_DIAGNOSTICS_DISABLED")
        }
        val evaluation = BackendCorrectnessGate.evaluate(
            EngineDiagnosticsStorage.readAllProcessLogs(context),
            cpuNativeRunId,
            vulkanNativeRunId
        )
        return BackendCorrectnessResult(
            status = evaluation.status,
            reason = evaluation.reason,
            comparedSteps = evaluation.comparedSteps,
            minimumTop5Overlap = evaluation.minimumTop5Overlap,
            firstDivergenceStep = evaluation.firstDivergenceStep
        )
    }

    /**
     * Returns the Q4_K decode path evidence emitted by the native Vulkan backend
     * for one inference. The run ID prevents a prior model/profile's selection
     * from being attributed to the current A/B sample.
     */
    fun vulkanQ4kDecodePathLines(context: Context, nativeRunId: String): List<String> {
        if (nativeRunId.isBlank()) return emptyList()
        val marker = "LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - "
        return EngineDiagnosticsStorage.readAllProcessLogs(context).filter { line ->
            if (!line.contains(marker)) return@filter false
            line.substringAfter(marker).startsWith("runId=$nativeRunId,")
        }
    }



    internal fun buildCompactReportForTest(lines: List<String>): String =
        EngineDiagnosticsReportBuilder.buildCompactReport(lines)

    fun exportTo(context: Context, destination: Uri): Result<Long> {
        return runCatching {
            info(
                "diagnostics",
                "EXPORT_REQUESTED",
                "destinationScheme=${destination.scheme ?: "unknown"}"
            )
            val sourceFiles = EngineDiagnosticsStorage.processLogFiles(context)
            val sourceInventory = sourceFiles.joinToString(";") { file ->
                "${file.name}:${file.length()}B"
            }.ifBlank { "none" }
            info(
                "diagnostics",
                "EXPORT_SOURCE_SCAN",
                "directoryExists=${EngineDiagnosticsStorage.diagnosticsDirectory(context).isDirectory},files=$sourceInventory"
            )
            val sourceLines = EngineDiagnosticsStorage.readAllProcessLogs(context)
            val content = EngineDiagnosticsReportBuilder.buildCompactReport(sourceLines)
            val bytes = content.toByteArray(Charsets.UTF_8)
            check(bytes.isNotEmpty()) { "Der Diagnosebericht ist leer; es wurden keine Exportdaten erzeugt." }

            EngineDiagnosticsStorage.writeAndVerifyExport(context, destination, bytes)

            info(
                "diagnostics",
                "EXPORT_COMPLETED",
                "bytes=${bytes.size},sourceLines=${sourceLines.size}"
            )
            Log.i(
                TAG,
                "Diagnostic export verified: bytes=${bytes.size}, sourceLines=${sourceLines.size}, sourceFiles=${sourceFiles.size}"
            )
            bytes.size.toLong()
        }.onFailure { error ->
            Log.e(TAG, "Diagnostic export failed", error)
        }
    }
}
