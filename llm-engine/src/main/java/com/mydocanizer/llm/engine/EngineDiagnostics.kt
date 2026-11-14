package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Diagnostic logger for Local LLM Engine lifecycle and inference metrics.
 * Does not store chat prompts or sensitive user text.
 */
object EngineDiagnostics {
    private const val TAG = "EngineDiagnostics"

    /**
     * Each process writes a separate file in shared app-private storage. This makes
     * :inference events exportable from the main process even after a service crash.
     * Prompt and response text are not stored.
     */
    fun initialize(context: Context) = EngineDiagnosticsStorage.initialize(context)

    fun info(tag: String, event: String, details: String = "") =
        EngineDiagnosticsStorage.info(tag, event, details)

    /**
     * Compatibility shim for legacy demo/test callers.
     *
     * Raw prompt, document, token-piece and generated-response text must never be
     * persisted by the engine diagnostics subsystem. Keep the signature so older
     * demo call sites remain source-compatible, but intentionally discard payloads.
     * Synthetic correctness evidence is recorded separately as numeric metadata.
     */
    @Deprecated("Raw text diagnostics are disabled; record bounded structured metadata instead.")
    @Suppress("UNUSED_PARAMETER")
    fun testText(tag: String, event: String, details: String) {
        // Intentionally empty: never forward caller-controlled text to info(), Log or disk.
    }
 
    /**
     * Persist only numeric token-ID/logit rows for the explicitly enabled synthetic
     * correctness gate. The native producer must not include token pieces or text.
     */
    internal fun recordSyntheticTokenSelectionTrace(
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
