package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Diagnostic logger for Local LLM Engine lifecycle and inference metrics.
 * Does not store chat prompts or sensitive user text.
 */
object EngineDiagnostics {
    private const val TAG = "EngineDiagnostics"
    private val timestampPrefix = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}""")

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



    private data class ResourceSample(
        val line: String,
        val timestamp: String,
        val pid: String,
        val phase: String,
        val pssMb: Double?,
        val nativePssMb: Double?,
        val nativeHeapMb: Double?,
        val javaHeapMb: Double?,
        val availableRamMb: Double?,
        val thermalStatus: String?
    )

    // Matrix rows and Vulkan profiles need more room than the old 32 KiB cap.
    // Keep the export bounded while retaining the complete structured evidence.
    private const val MAX_EXPORT_BYTES = 128 * 1024
    private const val MAX_EVENT_LINES = 24
    private const val MAX_NATIVE_LINES = 14
    private const val MAX_MEMORY_TIMELINE_LINES = 16
    private const val MAX_VULKAN_PROFILE_SNAPSHOTS = 12

    internal fun field(line: String, key: String): String? {
        val marker = "$key="
        val start = line.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val comma = line.indexOf(',', valueStart).takeIf { it >= 0 } ?: line.length
        val brace = line.indexOf('}', valueStart).takeIf { it >= 0 } ?: line.length
        return line.substring(valueStart, minOf(comma, brace)).takeIf { it.isNotBlank() }
    }

    private fun thermalRank(status: String): Int = when (status.uppercase(Locale.US)) {
        "NONE" -> 0
        "LIGHT" -> 1
        "MODERATE" -> 2
        "SEVERE" -> 3
        "CRITICAL" -> 4
        "EMERGENCY" -> 5
        "SHUTDOWN" -> 6
        else -> -1
    }

    private fun resourceSample(line: String): ResourceSample? {
        if (!line.contains("RESOURCE_SNAPSHOT")) return null
        return ResourceSample(
            line = line,
            timestamp = line.substringBefore(" ["),
            pid = field(line, "pid") ?: "unknown",
            phase = field(line, "phase") ?: "UNKNOWN",
            pssMb = field(line, "processPssMb")?.toDoubleOrNull(),
            nativePssMb = field(line, "nativePssMb")?.toDoubleOrNull(),
            nativeHeapMb = field(line, "nativeHeapAllocatedMb")?.toDoubleOrNull(),
            javaHeapMb = field(line, "javaHeapUsedMb")?.toDoubleOrNull(),
            availableRamMb = field(line, "systemAvailableRamMb")?.toDoubleOrNull(),
            thermalStatus = field(line, "thermalStatus")?.takeIf { thermalRank(it) >= 0 }
        )
    }

    private fun eventName(line: String): String =
        line.substringAfterLast("] ")
            .substringBefore(" - ")
            .trim()
            .uppercase(Locale.US)

    private fun isPerformanceEvent(line: String): Boolean {
        val event = eventName(line)
        return event in setOf(
            "NATIVE_BACKEND_PREFLIGHT",
            "MODEL_NATIVE_RUNTIME_READY",
            "PROMPT_STAGE_METRICS",
            "PREFILL_COMPLETED",
            "FIRST_TOKEN_RECEIVED",
            "GENERATION_COMPLETED"
        ) || event.startsWith("BACKEND_BENCHMARK_") ||
            event.startsWith("GPU_LAYER_SWEEP_") || event.startsWith("VULKAN_DMMV_SWEEP_") ||
            event.startsWith("VULKAN_Q4K_PATH_SWEEP_") || event.startsWith("FULL_MATRIX_") ||
            event.startsWith("BACKEND_ACCEPTANCE_") ||
            event.startsWith("CORRECTNESS_GATE_")
    }

    private fun isImportantEvent(line: String): Boolean {
        if (line.contains("[ERROR]") || line.contains("[WARN]")) return true

        // Log format is: timestamp [LEVEL] [tag] EVENT - details.
        // Only inspect the event token; free-form detail text must not promote
        // unrelated lines (for example a path containing the word "kill").
        val event = eventName(line)
        if (event.isBlank()) return false

        val exactEvents = setOf(
            "BACKEND_BENCHMARK",
            "MODEL_LOADED",
            "MODEL_UNLOAD",
            "SERVICE_CREATED",
            "SERVICE_DESTROYED",
            "SERVICE_DISCONNECTED",
            "ENGINE_INITIALIZATION",
            "INIT_FAILED",
            "INFERENCE_STARTED",
            "INFERENCE_COMPLETED",
            "INFERENCE_CANCELLED",
            "INFERENCE_TIMEOUT",
            "BACKEND_SELECTED",
            "BACKEND_INITIALIZED",
            "RESOURCE_PHASE",
            "PROCESS_DIED",
            "PROCESS_RESTART",
            "PROCESS_EXIT_INFO",
            "PROCESS_EXIT_INFO_UNAVAILABLE",
            "PROCESS_KILLED",
            "KILL_PROCESS_REQUESTED",
            "PROCESS_ABORT_REQUESTED",
            "PROCESS_ABORT_CONFIRMED",
            "PROCESS_ABORT_UNCONFIRMED",
            "PROCESS_ABORT_SKIPPED_REPLACEMENT",
            "EXPORT_REQUESTED",
            "EXPORT_COMPLETED",
            "COOPERATIVE_CANCEL_ESCALATED",
            "TIMEOUT_COOPERATIVE_CANCEL_SENT",
            "TIMEOUT_COOPERATIVE_CANCEL_ACKNOWLEDGED",
            "TIMEOUT_COOPERATIVE_CANCEL_ESCALATED",
            "SERVICE_RECONNECT_STARTED",
            "SERVICE_RECONNECT_BIND_REQUESTED",
            "SERVICE_RECONNECTED",
            "SERVICE_CONNECTED",
            "BINDING_DIED",
            "CANCEL_REQUESTED"
        )
        return event in exactEvents ||
            event.startsWith("MODEL_LOAD_") ||
            event.startsWith("MODEL_UNLOAD_") ||
            event.startsWith("PROMPT_EVALUATION_") ||
            event.endsWith("_FAILED") ||
            event.endsWith("_FAILURE") ||
            event.endsWith("_TIMEOUT") ||
            event.endsWith("_CANCELLED") ||
            event.endsWith("_CRASH") ||
            event.endsWith("_CRASHED") ||
            event.endsWith("_ABORTED")
    }

    private fun compactLine(line: String, maxChars: Int = 300): String =
        if (line.length <= maxChars) line else line.take(maxChars - 18) + " …[gekürzt]"

    private fun buildCompactReport(lines: List<String>): String {
        val ordered = lines.sorted()
        val kotlinLines = ordered.filterNot { it.contains("[NATIVE]") }
        val nativeLines = ordered.filter { it.contains("[NATIVE]") }
        val samples = kotlinLines.mapNotNull(::resourceSample)
        val events = kotlinLines
            .filterNot { it.contains("RESOURCE_SNAPSHOT") || isPerformanceEvent(it) }
            .filter(::isImportantEvent)
            .takeLast(MAX_EVENT_LINES)
            .map { compactLine(it) }
        // These low-frequency metrics are essential to compare Vulkan against CPU:
        // prefill/tokenization/decode times, generated tokens, generation wall time,
        // measured tokens/s, and the backend used for each test. Prompts/responses
        // are never included in these structured diagnostic events.
        // Keep the complete low-volume DMMV sweep outcome independently of
        // repeated per-profile model-load and inference events. Otherwise the
        // AUTO/SUBGROUP/LARGE result rows can be pushed out of the 12-row tail.
        val vulkanKernelSweepEvents = kotlinLines
            .filter {
                val event = eventName(it)
                event.startsWith("VULKAN_DMMV_SWEEP_") ||
                    event.startsWith("VULKAN_Q4K_PATH_SWEEP_")
            }
            .takeLast(12)
        // Keep every matrix profile row in the compact export. Each result carries
        // graphSplits and diagnosticRunId; the RAM summary below joins that ID to the
        // exact INFERENCE_<runId> Peak-PSS/available-RAM/thermal samples.
        // Keep one compact row per profile. PROFILE_STARTED and gate PASS events
        // are not matrix result rows; counting them against the 48-row cap silently
        // discarded the first half of a 48-profile Qwen3.5 matrix.
        val fullMatrixRows = kotlinLines
            .filter {
                eventName(it) == "FULL_MATRIX_RESULT" ||
                    eventName(it) == "FULL_MATRIX_ROW_INVALID" ||
                    eventName(it) == "BACKEND_ACCEPTANCE_RESULT" ||
                    eventName(it) == "BACKEND_ACCEPTANCE_ROW_INVALID"
            }
            .takeLast(48)
        val fullMatrixLifecycleEvents = kotlinLines
            .filter {
                eventName(it) in setOf(
                    "FULL_MATRIX_STARTED",
                    "FULL_MATRIX_SAFETY_STOP",
                    "FULL_MATRIX_CANCELLED",
                    "FULL_MATRIX_FAILED",
                    "FULL_MATRIX_RESTORED",
                    "FULL_MATRIX_RESTORE_FAILED",
                    "BACKEND_ACCEPTANCE_STARTED",
                    "BACKEND_ACCEPTANCE_SAFETY_STOP",
                    "BACKEND_ACCEPTANCE_CANCELLED",
                    "BACKEND_ACCEPTANCE_FAILED",
                    "BACKEND_ACCEPTANCE_RESTORED",
                    "BACKEND_ACCEPTANCE_RESTORE_FAILED"
                )
            }
            .takeLast(6)
        // PASS is already encoded in each FULL_MATRIX_RESULT row. Keep only recent
        // gate failures as supplemental detail so they cannot evict profile rows.
        val correctnessGateFailures = kotlinLines
            .filter {
                eventName(it) == "CORRECTNESS_GATE_RESULT" &&
                    field(it, "status") == "FAIL"
            }
            .takeLast(8)
        val performanceEvents = (
            kotlinLines
                .filterNot { it.contains("RESOURCE_SNAPSHOT") }
                .filter(::isPerformanceEvent)
                .filterNot {
                    val event = eventName(it)
                    event.startsWith("VULKAN_DMMV_SWEEP_") ||
                        event.startsWith("VULKAN_Q4K_PATH_SWEEP_") ||
                        event.startsWith("FULL_MATRIX_") ||
                        event.startsWith("BACKEND_ACCEPTANCE_") ||
                        event.startsWith("CORRECTNESS_GATE_") ||
                        event.endsWith("_TEST_OUTPUT")
                }
                .takeLast(12) + vulkanKernelSweepEvents
            )
            .distinct()
            .sorted()
            .map { compactLine(it, 520) }

        val nativeKeywords = listOf(
            "VULKAN", "GGML_VULKAN", "BACKEND", "DEVICE", "SUBGROUP",
            "TOKEN_STEP_METRIC", "MEMORY", "ALLOC", "PIPELINE", "SHADER", "FENCE", "QUEUE", "PERF_PROFILER",
            "FAILED", "ERROR", "WARN", "UNSUPPORTED", "FALLBACK",
            "OFFLOAD", "BUFFER", "LAYER", "OUT OF MEMORY", "DEVICE LOST",
            "CPU_RUNTIME_FEATURES", "ABORT_CALLBACK_POLICY", "GRAPH_SPLITS",
            "GATED_DELTA_NET", "QWEN35_GDN",
            "GRAPH_BACKEND_DISTRIBUTION"
        )
        val relevantNative = nativeLines
            .filterNot {
                it.contains("TOKEN_STEP_METRIC") ||
                    it.contains("LOCAL_LLM_VULKAN_OP_TIMING") ||
                    it.contains("LOCAL_LLM_TEXT_TRACE") ||
                    it.contains("kind=TOKEN_SELECTION")
            }
            .filter { line ->
            val upper = line.uppercase(Locale.US)
            nativeKeywords.any(upper::contains)
        }
        val nativeTokenMetricLines = nativeLines.filter { it.contains("TOKEN_STEP_METRIC") }
        val nativeTokenMetrics = nativeTokenMetricLines.mapNotNull { EngineDiagnosticsMetrics.nativeTokenStepMetric(it) }
        val legacyNativeMetricCount = nativeTokenMetricLines.size - nativeTokenMetrics.size
        val kotlinTokenMetrics = kotlinLines.mapNotNull { EngineDiagnosticsMetrics.kotlinTokenStepMetric(it) }
        // These two runtime facts are acceptance-critical and must never be lost
        // behind the verbose token/RAM sections in the 16 KiB compact export:
        //  - ggml_vulkan's live driver capability (especially "int dot")
        //  - llama.cpp's actual model-layer assignment, not our requested layer count.
        val vulkanCapabilityEvidence = nativeLines
            .filter { it.contains("ggml_vulkan:", ignoreCase = true) &&
                it.contains("int dot:", ignoreCase = true) }
            .takeLast(2)
        val actualLayerOffloadEvidence = nativeLines
            .filter { line ->
                val upper = line.uppercase(Locale.US)
                upper.contains("OFFLOADING OUTPUT LAYER TO GPU") ||
                    (upper.contains("OFFLOADED ") && upper.contains(" LAYERS TO GPU"))
            }
            .takeLast(3)
        val q4kPrefillEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH") }
            .takeLast(4)
        val q4kDecodeEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH") }
            .takeLast(4)
        val dmmvWorkgroupEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_VULKAN_DMMV_WG_SELECTED") }
            .takeLast(4)
        val memoryBudgetEvidence = nativeLines
            .filter { line ->
                line.contains("LOCAL_LLM_MEMORY_BUDGET") ||
                    line.contains("LOCAL_LLM_MEMORY_HEAP")
            }
            .takeLast(4)
        val profilerConfigEvidence = nativeLines
            .filter { it.contains("VULKAN_PERF_PROFILER_CONFIG") }
            .takeLast(2)
        val cpuFeatureEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_CPU_RUNTIME_FEATURES") }
            .takeLast(2)
        val abortPolicyEvidence = nativeLines
            .filter { it.contains("LLAMA_ABORT_CALLBACK_POLICY") }
            .takeLast(2)
        val graphSplitEvidence = nativeLines
            .filter { it.contains("graphSplits=") }
            .takeLast(8)
        // Keep per-run CPU/Vulkan node assignments; total splits alone do not identify fallback.
        val graphBackendDistributionEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION") }
            .takeLast(72)
        val qwen35GdnPolicyEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_QWEN35_GDN_POLICY") }
            .takeLast(4)
        // Keep prompt/template evidence separate from per-token selection rows. The
        // latter are compact and bounded so a short broken answer still shows where
        // the selected tokens diverge from the model's top logits.
        val nativePriorityEvidence = (
            profilerConfigEvidence + vulkanCapabilityEvidence + actualLayerOffloadEvidence +
                q4kPrefillEvidence + q4kDecodeEvidence + dmmvWorkgroupEvidence + memoryBudgetEvidence +
                cpuFeatureEvidence + abortPolicyEvidence + graphSplitEvidence +
                graphBackendDistributionEvidence + qwen35GdnPolicyEvidence
            ).distinct().map { compactLine(it, 360) }

        // The upstream Vulkan profiler emits one timestamp-query row per operator.
        // Keep only the newest snapshots, then report per-op p50/p90 instead of
        // dumping hundreds of raw lines into the compact export.
        val allVulkanOperationSamples = nativeLines.mapNotNull { EngineDiagnosticsMetrics.vulkanOperationSample(it) }
        // Retain the newest snapshots independently for each process/run pair.
        // Otherwise one later benchmark run would evict the earlier run before
        // the per-run grouping below has a chance to compare them.
        val recentSnapshotKeys = allVulkanOperationSamples
            .groupBy { "${it.pid}/${it.runId}" }
            .values
            .flatMap { runSamples ->
                runSamples
                    .map { "${it.pid}/${it.runId}/${it.snapshot}" }
                    .distinct()
                    .takeLast(MAX_VULKAN_PROFILE_SNAPSHOTS)
            }
            .toSet()
        val recentVulkanOperationSamples = allVulkanOperationSamples.filter {
            "${it.pid}/${it.runId}/${it.snapshot}" in recentSnapshotKeys
        }
        val vulkanOperationSummary = buildString {
            if (recentVulkanOperationSamples.isEmpty()) {
                appendLine("Keine Vulkan-Per-Operation-Timestamps gefunden. Prüfe VULKAN_PERF_PROFILER_CONFIG und einen Vulkan-Inferenzlauf.")
            } else {
                val groupedOperations = recentVulkanOperationSamples
                    .groupBy { Triple(it.pid, it.runId, it.operation) }
                    .map { (key, metrics) ->
                        Triple(key, metrics, metrics.sumOf { it.totalUs })
                    }
                    .sortedByDescending { it.third }
                    .take(20)
                groupedOperations.forEach { (key, metrics, summedUs) ->
                    val uniqueSnapshots = metrics.map { it.snapshot }.distinct().size
                    val totalSamples = metrics.sumOf { it.sampleCount }
                    val perGraphTotalUs = if (uniqueSnapshots > 0) summedUs / uniqueSnapshots else summedUs
                    val gflops = metrics.mapNotNull { it.gflops }
                    appendLine(
                        "pid=${key.first} runId=${key.second} op=${key.third} snapshots=$uniqueSnapshots samples=$totalSamples " +
                            doubleTimingSummary("gpuAvg", metrics.map { it.averageUs }) +
                            " gpuPerGraphUs=${String.format(Locale.US, "%.3f", perGraphTotalUs)}" +
                            " gpuSumUs=${String.format(Locale.US, "%.3f", summedUs)}" +
                            (if (gflops.isNotEmpty()) {
                                " gflops_p50=${String.format(Locale.US, "%.3f", EngineDiagnosticsMetrics.percentileDouble(gflops, 0.50))}"
                            } else "")
                    )
                }
            }
        }

        val nativeSelected = (
            relevantNative.take(10) + relevantNative.takeLast(4)
        ).distinct().map { compactLine(it, 360) }
        val legacySyncLabelSamples = nativeTokenMetrics.count { it.usedLegacyGpuSyncWaitLabel }
        val tokenMetricSummary = buildString {
            if (nativeTokenMetrics.isEmpty()) {
                appendLine("Keine eindeutig einem Inferenzlauf zuordenbaren nativen Token-Messwerte gefunden.")
            } else {
                appendLine("Native Decode-Zeiten – strikt nach PID, Backend und runId getrennt:")
                nativeTokenMetrics
                    .groupBy { "${it.pid}/${it.backend}/${it.runId}" }
                    .toSortedMap()
                    .forEach { (_, metrics) ->
                        val first = metrics.first()
                        appendLine("pid=${first.pid} backend=${first.backend} runId=${first.runId} samples=${metrics.size}")
                        appendLine(EngineDiagnosticsMetrics.timingSummary("sample", metrics.map { it.sampleUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("decode", metrics.map { it.decodeUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("decodeSubmit", metrics.map { it.decodeSubmitUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("backendSyncWait", metrics.map { it.backendSyncWaitUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("tokenPiece", metrics.map { it.pieceUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("nativeStep", metrics.map { it.totalUs }))
                        appendLine("Token-Schritte (index/sampleUs/decodeUs/submitUs/backendSyncWaitUs/pieceUs/totalUs):")
                        val orderedMetrics = metrics.sortedBy { it.index }
                        val representativeMetrics = if (orderedMetrics.size <= 8) {
                            orderedMetrics
                        } else {
                            (orderedMetrics.take(4) + orderedMetrics.takeLast(4)).distinctBy { it.index }
                        }
                        representativeMetrics.forEach { metric ->
                            appendLine("${metric.index}/${metric.sampleUs}/${metric.decodeUs}/${metric.decodeSubmitUs}/${metric.backendSyncWaitUs}/${metric.pieceUs}/${metric.totalUs}")
                        }
                    }
            }
            if (legacySyncLabelSamples > 0) {
                appendLine(
                    "Hinweis: $legacySyncLabelSamples ältere Samples heißen gpuSyncWaitUs; " +
                        "sie messen ebenfalls die gesamte llama_synchronize()-Dauer und werden hier " +
                        "als backendSyncWait interpretiert, nicht als reine GPU-Kernelzeit."
                )
            }
            if (legacyNativeMetricCount > 0) {
                appendLine("Legacy native samples without pid/runId/backend correlation (excluded from percentiles): $legacyNativeMetricCount")
            }
            appendLine()
            appendLine("Kotlin/JNI-Wall-Zeiten – strikt nach PID, Backend und runId getrennt:")
            if (kotlinTokenMetrics.isEmpty()) {
                appendLine("Keine eindeutig zuordenbaren Kotlin/JNI-Token-Messwerte gefunden.")
            } else {
                kotlinTokenMetrics
                    .groupBy { "${it.pid}/${it.backend}/${it.runId}" }
                    .toSortedMap()
                    .forEach { (_, metrics) ->
                        val first = metrics.first()
                        appendLine("pid=${first.pid} backend=${first.backend} runId=${first.runId} samples=${metrics.size}")
                        appendLine(EngineDiagnosticsMetrics.timingSummary("jniWall", metrics.map { it.jniWallUs }))
                        val orderedMetrics = metrics.sortedBy { it.index }
                        val representativeMetrics = if (orderedMetrics.size <= 8) {
                            orderedMetrics
                        } else {
                            (orderedMetrics.take(4) + orderedMetrics.takeLast(4)).distinctBy { it.index }
                        }
                        representativeMetrics.forEach { metric ->
                            appendLine("nativeStep=${metric.index} jniWallUs=${metric.jniWallUs}")
                        }
                    }
            }
        }

        val memorySummaries = samples
            .groupBy { "${it.pid}/${it.phase}" }
            .toSortedMap()
            .map { (group, entries) ->
                fun peak(selector: (ResourceSample) -> Double?): String =
                    entries.mapNotNull(selector).maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
                val minimumAvailable = entries.mapNotNull { it.availableRamMb }.minOrNull()
                    ?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
                val thermalStates = entries.mapNotNull { it.thermalStatus }.distinct()
                val thermalPeak = thermalStates.maxByOrNull(::thermalRank) ?: "n/a"
                val last = entries.lastOrNull()
                val model = last?.let { field(it.line, "model") }
                val context = last?.let { field(it.line, "contextTokens") }
                val batch = last?.let { field(it.line, "batchSize") }
                val microBatch = last?.let { field(it.line, "microBatchSize") }
                val layers = last?.let { field(it.line, "gpuLayersRequested") }
                "$group n=${entries.size} PSS_peak=${peak { it.pssMb }}MB " +
                    "nativePSS_peak=${peak { it.nativePssMb }}MB nativeHeap_peak=${peak { it.nativeHeapMb }}MB " +
                    "javaHeap_peak=${peak { it.javaHeapMb }}MB availableRAM_min=${minimumAvailable}MB " +
                    "thermal_peak=$thermalPeak thermal_states=${thermalStates.joinToString("/")}" +
                    listOfNotNull(model?.let { "model=$it" }, context?.let { "ctx=$it" },
                        batch?.let { "batch=$it" }, microBatch?.let { "ubatch=$it" },
                        layers?.let { "gpuLayers=$it" }).joinToString(" ")
            }

        val memoryTimeline = samples
            .groupBy { "${it.pid}/${it.phase}" }
            .values
            .flatMap { group ->
                val indices = buildSet {
                    if (group.isNotEmpty()) {
                        add(0)
                        add(group.lastIndex)
                        group.indices.filter { it % 15 == 0 }.forEach(::add)
                    }
                    group.indices.maxByOrNull { group[it].pssMb ?: Double.MIN_VALUE }?.let(::add)
                    group.indices.minByOrNull { group[it].availableRamMb ?: Double.MAX_VALUE }?.let(::add)
                    group.indices.filter { group[it].line.contains("backendMemory=") }.lastOrNull()?.let(::add)
                }
                indices.mapNotNull { group.getOrNull(it) }
            }
            .distinctBy { it.line }
            .sortedBy { it.timestamp }
            .takeLast(MAX_MEMORY_TIMELINE_LINES)
            .map { sample ->
                val values = listOf(
                    "pss=${sample.pssMb?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}MB",
                    "nativeHeap=${sample.nativeHeapMb?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}MB",
                    "availRAM=${sample.availableRamMb?.let { "%.1f".format(Locale.US, it) } ?: "n/a"}MB",
                    sample.thermalStatus?.let { "thermal=$it" },
                    field(sample.line, "contextTokens")?.let { "ctx=$it" },
                    field(sample.line, "microBatchSize")?.let { "ubatch=$it" },
                    field(sample.line, "gpuLayersRequested")?.let { "gpuLayers=$it" },
                    field(sample.line, "backendActive")?.let { "backend=$it" }
                ).filterNotNull().joinToString(" ")
                val backend = if (sample.line.contains("backendMemory=")) {
                    " " + sample.line.substringAfter("backendMemory=").take(200)
                } else ""
                "${sample.timestamp} pid=${sample.pid} ${sample.phase} $values$backend"
            }

        val timestampedRows = ordered.mapNotNull { line ->
            timestampPrefix.find(line)?.value
        }
        val periodStart = timestampedRows.minOrNull() ?: "n/a"
        val periodEnd = timestampedRows.maxOrNull() ?: "n/a"
        val latestGdnPolicy = qwen35GdnPolicyEvidence.lastOrNull()
            ?.substringAfter("LOCAL_LLM_QWEN35_GDN_POLICY - ")
            ?.replace("\\n", "")
            ?.trim()
            ?: "n/a"
        val latestGraphDistribution = graphBackendDistributionEvidence.lastOrNull()
        fun nodePercent(nodes: Int?, total: Int?): String =
            if (nodes == null || total == null || total <= 0) "n/a"
            else String.format(Locale.US, "%.1f%%", nodes * 100.0 / total)
        val graphSummary = latestGraphDistribution?.let { line ->
            val total = field(line, "graphNodes")?.toIntOrNull()
            val cpu = field(line, "cpuNodes")?.toIntOrNull()
            val vulkan = field(line, "vulkanNodes")?.toIntOrNull()
            val other = field(line, "otherNodes")?.toIntOrNull()
            "graphSplits=${field(line, "graphSplits") ?: "n/a"},graphNodes=${total ?: "n/a"}," +
                "cpuNodes=${cpu ?: "n/a"}(${nodePercent(cpu, total)})," +
                "vulkanNodes=${vulkan ?: "n/a"}(${nodePercent(vulkan, total)})," +
                "otherNodes=${other ?: "n/a"}(${nodePercent(other, total)})"
        } ?: "graphSplits=n/a,graphNodes=n/a,cpuNodes=n/a,vulkanNodes=n/a,otherNodes=n/a"
        val correctnessGateEvents = kotlinLines
            .filter { eventName(it) == "CORRECTNESS_GATE_RESULT" }
        val gateStatusSummary = correctnessGateEvents
            .groupingBy { field(it, "status") ?: "UNKNOWN" }
            .eachCount()
            .toSortedMap()
            .map { (status, count) -> "$status=$count" }

        return buildString {
            appendLine("LOCAL LLM ENGINE – KOMPAKTER DIAGNOSEBERICHT")
            appendLine("Erstellt: ${Date()}")
            appendLine("Erfasste Logzeilen: ${ordered.size}; RAM-Samples: ${samples.size}")
            appendLine("Zeitraum: $periodStart bis $periodEnd")
            appendLine("gdnPolicy=$latestGdnPolicy")
            appendLine("graphBackend=$graphSummary")
            appendLine("Gate-Status:")
            if (gateStatusSummary.isEmpty()) appendLine("Keine Korrektheits-Gate-Ergebnisse vorhanden.")
            else gateStatusSummary.forEach(::appendLine)
            appendLine()
            appendLine("[CPU/VULKAN-ABNAHMETEST UND ÄLTERE MATRIX – PROFILERGEBNISSE]")
            if (fullMatrixRows.isEmpty()) {
                appendLine("Keine Matrix-Profilzeilen vorhanden.")
            } else {
                fullMatrixRows.forEach { appendLine(compactLine(it, 360)) }
            }
            fullMatrixLifecycleEvents.forEach { appendLine(compactLine(it, 220)) }
            if (correctnessGateFailures.isNotEmpty()) {
                appendLine("Zusätzliche Korrektheits-Gate-Fehlerdetails:")
                correctnessGateFailures.forEach { appendLine(compactLine(it, 300)) }
            }
            appendLine()
            appendLine("[VULKAN-LAUFZEITBELEG – TREIBER, LAYER-OFFLOAD, Q4_K-/DMMV-KERNELPFAD UND SPEICHERBUDGET]")
            if (nativePriorityEvidence.isEmpty()) {
                appendLine("Keine passende Runtime-Zeile für Mali-Treiberfähigkeiten oder tatsächliche Layer-Zuweisung im nativen Log gefunden.")
            } else {
                nativePriorityEvidence.forEach(::appendLine)
            }
            appendLine()
            appendLine("[VULKAN-GPU-OPERATIONSPROFIL – LETZTE ${MAX_VULKAN_PROFILE_SNAPSHOTS} GRAPH-SNAPSHOTS]")
            vulkanOperationSummary.lineSequence().forEach { appendLine(it) }
            appendLine()
            appendLine("[RAM-ZUSAMMENFASSUNG – SPITZENWERTE PRO PHASE]")
            if (memorySummaries.isEmpty()) appendLine("Keine RAM-Samples gefunden.")
            memorySummaries.forEach(::appendLine)
            appendLine()
            appendLine("[RAM-ZEITVERLAUF – AUSGEDÜNNT, SPITZEN UND BACKEND-SAMPLES]")
            memoryTimeline.forEach(::appendLine)
            appendLine()
            appendLine("[BACKEND- UND INFERENZ-MESSWERTE]")
            if (performanceEvents.isEmpty()) {
                appendLine("Keine abgeschlossenen Backend-/Inferenz-Messwerte gefunden.")
            }
            performanceEvents.forEach(::appendLine)
            appendLine()
            appendLine("[WICHTIGE EREIGNISSE]")
            if (events.isEmpty()) appendLine("Keine passenden Ereignisse.")
            events.forEach(::appendLine)
            appendLine()
            appendLine("[NATIVE TOKEN-DECODE-ZEITEN – STICHPROBEN]")
            tokenMetricSummary.lineSequence().forEach { appendLine(it) }
            appendLine()
            appendLine("[TESTAUSGABEN – IM STANDARD-EXPORT AUSGEBLENDET]")
            appendLine("Prompt-Inhalte, Token-Text und generierte Testantworten werden nicht exportiert.")
            appendLine()
            appendLine("[NATIVE / VULKAN – GEFILTERTE MELDUNGEN]")
            if (nativeSelected.isEmpty()) appendLine("Keine passenden nativen Backend-Meldungen gefunden.")
            nativeSelected.forEach(::appendLine)
            appendLine()
            appendLine(
                if (BuildConfig.DEBUG && BuildConfig.TEXT_DIAGNOSTICS_ENABLED) {
                    "Hinweis: Debug-Textdiagnostik ist aktiv. Rohdaten bleiben auf Debug-Logs begrenzt; " +
                        "der Standard-Export enthält keine Prompt-Inhalte, Token-Texte oder Testantworten. Exportlimit: 128 KiB."
                } else {
                    "Hinweis: Chat-Prompts und Modellantworten werden nicht exportiert. " +
                        "Der Bericht ist auf maximal 128 KiB begrenzt; GPU-Operatorzeiten werden aggregiert und RAM-Samples zusammengefasst."
                }
            )
        }.let { limitUtf8(it, MAX_EXPORT_BYTES) }
    }

    internal fun buildCompactReportForTest(lines: List<String>): String = buildCompactReport(lines)

    private fun limitUtf8(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        val suffix = "\n[Export begrenzt: weitere Einträge wurden abgeschnitten]\n"
        val limit = (maxBytes - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        var end = limit.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + suffix
    }

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
            val content = buildCompactReport(sourceLines)
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
