package com.mydocanizer.llm.engine

import java.util.Date
import java.util.Locale

/**
 * Builds a compact, human-readable diagnostics overview from bounded process logs.
 * Generated assistant responses and explicit test input/output are retained. Debug
 * diagnostic builds also export bounded raw prompt/template/token evidence; Release
 * builds keep raw text diagnostics disabled.
 */
internal object EngineDiagnosticsReportBuilder {
    private val timestampPrefix = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}""")

    internal data class ResourceSample(
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
    private const val MAX_NATIVE_TEXT_EVIDENCE_CHARS = 24 * 1024

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
            event.startsWith("CORRECTNESS_GATE_") ||
            (event.startsWith("CONFIG_SMOKE_") && !event.endsWith("_TEST_OUTPUT"))
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
            "INFERENCE_TIMEOUT_POLICY",
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
            "CANCEL_REQUESTED",
            "CONFIG_SMOKE_STARTED",
            "CONFIG_SMOKE_RESULT",
            "CONFIG_SMOKE_SAFETY_STOP",
            "CONFIG_SMOKE_CANCELLED",
            "CONFIG_SMOKE_FAILED",
            "CONFIG_SMOKE_RESTORED",
            "CONFIG_SMOKE_RESTORE_FAILED",
            "CONFIG_SMOKE_CANCEL_REQUESTED",
            "CONFIG_SMOKE_INFERENCE_CANCELLED",
            "CONFIG_SMOKE_OUTPUT_CHECK_FAILED",
            "OUTPUT_GATE_RESULT"
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

    private val eventLinePattern = Regex(
        """^([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}) \[(INFO|WARN|ERROR|DEBUG)] \[([^]]+)] ([A-Z0-9_]+)(?: - (.*))?$"""
    )
    private val fieldSeparator = Regex(""",(?=[A-Za-z][A-Za-z0-9_]*=)""")

    private fun readableEventTitle(event: String): String = when (event) {
        "CONFIG_SMOKE_STARTED" -> "Vulkan-Kurztest gestartet"
        "CONFIG_SMOKE_RESULT" -> "Vulkan-Kurztest abgeschlossen"
        "OUTPUT_GATE_RESULT" -> "Ausgabekorrektheits-Gate"
        "CONFIG_SMOKE_FAILED" -> "Vulkan-Kurztest fehlgeschlagen"
        "CONFIG_SMOKE_SAFETY_STOP" -> "Vulkan-Kurztest aus Sicherheitsgrund gestoppt"
        "CONFIG_SMOKE_CANCELLED" -> "Vulkan-Kurztest abgebrochen"
        "CONFIG_SMOKE_RESTORED" -> "Ursprüngliche Modellkonfiguration wiederhergestellt"
        "CONFIG_SMOKE_RESTORE_FAILED" -> "Wiederherstellung fehlgeschlagen"
        "PREFILL_COMPLETED" -> "Prompt-Verarbeitung abgeschlossen"
        "FIRST_TOKEN_RECEIVED" -> "Erstes Token empfangen"
        "GENERATION_COMPLETED" -> "Textgenerierung abgeschlossen"
        "MODEL_LOADED" -> "Modell geladen"
        "MODEL_LOAD_FAILED" -> "Modell konnte nicht geladen werden"
        "NATIVE_BACKEND_PREFLIGHT" -> "Backend- und Geräteprüfung"
        "MODEL_NATIVE_RUNTIME_READY" -> "Native Laufzeit bereit"
        "INFERENCE_TIMEOUT" -> "Zeitüberschreitung bei der Inferenz"
        "PROCESS_ABORT_CONFIRMED" -> "Inference-Prozess wurde beendet"
        "CORRECTNESS_GATE_RESULT" -> "Korrektheitsprüfung"
        "BACKEND_ACCEPTANCE_RESULT" -> "Backend-Abnahmetest abgeschlossen"
        "BACKEND_BENCHMARK_RESULT" -> "Backend-Benchmark abgeschlossen"
        "FULL_MATRIX_RESULT" -> "Matrix-Profil abgeschlossen"
        else -> event.lowercase(Locale.GERMAN).split('_')
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase(Locale.GERMAN) } }
    }

    private val readableFieldNames = mapOf(
        "backend" to "Backend",
        "activeBackend" to "Aktives Backend",
        "model" to "Modell",
        "contextSize" to "Kontextgröße",
        "contextTokens" to "Kontext-Tokens",
        "batchSize" to "Batchgröße",
        "microBatchSize" to "Microbatch",
        "gpuLayers" to "GPU-Layer",
        "gpuLayersRequested" to "Angeforderte GPU-Layer",
        "measuredTokensPerSecond" to "Gemessene Tokens/s",
        "decodeTokensPerSecond" to "Decode-Tokens/s",
        "nativeTokensPerSecond" to "Native Tokens/s",
        "promptTokens" to "Prompt-Tokens",
        "tokensGenerated" to "Erzeugte Tokens",
        "finishReason" to "Abschlussgrund",
        "status" to "Status",
        "reason" to "Begründung",
        "prefillMs" to "Prefill-Zeit (ms)",
        "ttftMs" to "Zeit bis erstes Token (ms)",
        "loadMs" to "Ladezeit (ms)",
        "graphSplits" to "Graph-Splits",
        "cpuNodes" to "CPU-Knoten",
        "vulkanNodes" to "Vulkan-Knoten",
        "systemAvailableRamMb" to "Verfügbarer RAM (MB)",
        "processPssMb" to "Prozess-PSS (MB)"
    )

    private fun readableFields(details: String): List<String> =
        details.split(fieldSeparator)
            .filterNot { field ->
                val key = field.substringBefore('=')
                key in setOf("schemaVersion", "eventId", "pid", "correlationType", "correlationId")
            }
            .take(16)
            .map { field ->
                val key = field.substringBefore('=')
                val value = field.substringAfter('=', "")
                val label = readableFieldNames[key]
                if (label == null || value.isEmpty()) "• $field"
                else "• $label: $value  [$key=$value]"
            }

    private fun readableEventLine(line: String, maxChars: Int = 560): String {
        val match = eventLinePattern.matchEntire(line) ?: return compactLine(line, maxChars)
        val timestamp = match.groupValues[1]
        val level = match.groupValues[2]
        val tag = match.groupValues[3]
        val event = match.groupValues[4]
        val details = match.groupValues.getOrElse(5) { "" }
        val fields = readableFields(details)
        return buildString {
            append(timestamp)
            append("  ")
            append(level.padEnd(5))
            append("  ")
            append(tag)
            append("  ")
            append(readableEventTitle(event))
            append(" [")
            append(event)
            append(']')
            if (fields.isNotEmpty()) {
                fields.forEach { field ->
                    appendLine()
                    append("    ")
                    append(compactLine(field, maxChars))
                }
            }
        }
    }

    private fun readableNativeLine(line: String, maxChars: Int = 420): String {
        val timestamp = timestampPrefix.find(line)?.value ?: ""
        val body = line.substringAfter(" [NATIVE] ", line).trim()
        val header = body.substringBefore(" - ").replace(Regex("\\s+"), " ")
        val details = body.substringAfter(" - ", "")
        val fields = readableFields(details)
        return buildString {
            if (timestamp.isNotBlank()) append(timestamp).append("  ")
            append("NATIVE  ").append(header)
            fields.forEach { field ->
                appendLine()
                append("    ").append(compactLine(field, maxChars))
            }
            if (fields.isEmpty() && details.isNotBlank()) {
                appendLine().append("    ").append(compactLine(details, maxChars))
            }
        }
    }

    internal fun buildCompactReport(lines: List<String>): String {
        // Stable timestamp sorting preserves the original order of multi-part text
        // events that share the same millisecond timestamp.
        val ordered = lines.sortedBy { timestampPrefix.find(it)?.value ?: "" }
        val kotlinLines = ordered.filterNot { it.contains("[NATIVE]") }
        val nativeLines = ordered.filter { it.contains("[NATIVE]") }
        val samples = kotlinLines.mapNotNull(::resourceSample)
        val events = kotlinLines
            .filterNot { it.contains("RESOURCE_SNAPSHOT") || isPerformanceEvent(it) }
            .filter(::isImportantEvent)
            .takeLast(MAX_EVENT_LINES)
            .map { readableEventLine(it) }
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
                    "FULL_MATRIX_PROFILE_STARTED",
                    "FULL_MATRIX_RESTORED",
                    "FULL_MATRIX_RESTORE_FAILED",
                    "BACKEND_ACCEPTANCE_STARTED",
                    "BACKEND_ACCEPTANCE_SAFETY_STOP",
                    "BACKEND_ACCEPTANCE_CANCELLED",
                    "BACKEND_ACCEPTANCE_FAILED",
                    "BACKEND_ACCEPTANCE_PROFILE_STARTED",
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
        // A PASS row is already represented by a matching matrix/backend-acceptance
        // result. Preserve standalone gate evidence when no such result row exists.
        fun correctnessGateKey(line: String, statusField: String): String? {
            val profile = field(line, "profile")?.uppercase(Locale.US) ?: return null
            val backend = field(line, "backend")?.uppercase(Locale.US) ?: return null
            val status = field(line, statusField)?.uppercase(Locale.US) ?: return null
            return "$profile/$backend/$status"
        }
        val representedGateResults = fullMatrixRows.mapNotNull { correctnessGateKey(it, "gateStatus") }.toSet()
        val standaloneCorrectnessGatePasses = kotlinLines
            .filter {
                eventName(it) == "CORRECTNESS_GATE_RESULT" &&
                    field(it, "status") == "PASS"
            }
            .filterNot { correctnessGateKey(it, "status") in representedGateResults }
            .takeLast(8)
        val inferenceOutputEvents = kotlinLines.filter {
            eventName(it).startsWith("INFERENCE_OUTPUT")
        }
        val recentOutputRunIds = inferenceOutputEvents
            .mapNotNull { field(it, "runId") }
            .distinct()
            .takeLast(3)
            .toSet()
        val inferenceOutputEvidence = inferenceOutputEvents.filter {
            field(it, "runId") in recentOutputRunIds
        }
        val explicitTestTextEvidence = kotlinLines.filter {
            val event = eventName(it)
            event.startsWith("CONFIG_SMOKE_TEST_INPUT") ||
                event.startsWith("CONFIG_SMOKE_TEST_OUTPUT")
        }.takeLast(24)
        val tokenSelectionEvidence = kotlinLines
            .filter { eventName(it) == "TOKEN_SELECTION_TRACE" }
            .takeLast(32)
        val selectedTextEvents = (inferenceOutputEvidence + explicitTestTextEvidence + tokenSelectionEvidence).toHashSet()
        // Filter the already timestamp-ordered source rather than sorting complete
        // log lines lexically: PART_10 must never move ahead of PART_2.
        val textEvidence = kotlinLines
            .filter { it in selectedTextEvents }
            .map { compactLine(it, 3_000) }

        val nativeTextTraceEvents = nativeLines.filter {
            it.contains("LOCAL_LLM_TEXT_TRACE - ")
        }
        val nativeTraceRunIds = nativeTextTraceEvents
            .mapNotNull { field(it, "runId") }
            .distinct()
        // Keep native prompt/token evidence for the same recent assistant outputs
        // shown above, even when later diagnostic-only runs add newer trace IDs.
        // Also retain the last two native-only runs (for tests without an output row).
        val recentNativeTraceRunIds = (
            nativeTraceRunIds.filter { it in recentOutputRunIds } +
                nativeTraceRunIds.takeLast(2)
            ).toSet()
        val nativeTextTraceCandidates = nativeTextTraceEvents.filter { line ->
            field(line, "runId") in recentNativeTraceRunIds &&
                (field(line, "kind") != "TOKEN_PIECE" ||
                    (field(line, "step")?.toIntOrNull()?.let { it <= 8 || it % 64 == 0 } == true)) &&
                !(field(line, "kind") == "FINAL_ASSISTANT_OUTPUT" &&
                    field(line, "runId") in recentOutputRunIds)
        }
        var nativeTextBudget = MAX_NATIVE_TEXT_EVIDENCE_CHARS
        val nativeTextEvidence = buildList {
            for (line in nativeTextTraceCandidates) {
                if (nativeTextBudget <= 0) break
                val compact = compactLine(line, 3_000)
                if (compact.length <= nativeTextBudget) {
                    add(compact)
                    nativeTextBudget -= compact.length
                } else {
                    add(compact.take(nativeTextBudget) + " …[native text evidence truncated]")
                    nativeTextBudget = 0
                }
            }
        }
        val kotlinInferenceContextEvidence = kotlinLines
            .filter {
                eventName(it) in setOf(
                    "MODEL_IDENTITY",
                    "MODEL_SHA256_COMPLETED",
                    "MODEL_SHA256_REUSED_IMPORT_DIGEST",
                    "MODEL_SHA256_SKIPPED_DIRECT_SOURCE",
                    "MODEL_LOAD_STARTED",
                    "MODEL_NATIVE_RUNTIME_READY",
                    "INFERENCE_RUN_CONTEXT"
                )
            }
            .takeLast(24)
            .map { compactLine(it, 2_000) }
        val nativeInferenceSetupEvidence = nativeLines
            .filter {
                it.contains("LOCAL_LLM_PROMPT_METADATA") ||
                    it.contains("LOCAL_LLM_SAMPLER_PARAMETERS") ||
                    it.contains("LOCAL_LLM_TEMPLATE_RENDER_ATTEMPT")
            }
            .takeLast(16)
            .map { compactLine(it, 1_200) }

        val inferenceSetupEvidence = kotlinInferenceContextEvidence + nativeInferenceSetupEvidence

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
            .map { readableEventLine(it, 620) }

        val nativeKeywords = listOf(
            "VULKAN", "GGML_VULKAN", "BACKEND", "DEVICE", "SUBGROUP",
            "TOKEN_STEP_METRIC", "MEMORY", "ALLOC", "PIPELINE", "SHADER", "FENCE", "QUEUE", "PERF_PROFILER",
            "FAILED", "ERROR", "WARN", "UNSUPPORTED", "FALLBACK",
            "OFFLOAD", "BUFFER", "LAYER", "OUT OF MEMORY", "DEVICE LOST",
            "CPU_RUNTIME_FEATURES", "ABORT_CALLBACK_POLICY", "GRAPH_SPLITS",
            "GATED_DELTA_NET", "QWEN35_GDN",
            "GRAPH_BACKEND_DISTRIBUTION", "GRAPH_OP_BACKEND_DISTRIBUTION"
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
        val graphOpBackendDistributionEvidence = nativeLines
            .filter { it.contains("LOCAL_LLM_GRAPH_OP_BACKEND_DISTRIBUTION") }
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
                graphBackendDistributionEvidence + graphOpBackendDistributionEvidence + qwen35GdnPolicyEvidence
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
                            EngineDiagnosticsMetrics.doubleTimingSummary("gpuAvg", metrics.map { it.averageUs }) +
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
                        val queueSubmitSamples = metrics.mapNotNull { it.vulkanQueueSubmitUs }
                        if (queueSubmitSamples.isNotEmpty()) {
                            appendLine(EngineDiagnosticsMetrics.timingSummary("vkQueueSubmit", queueSubmitSamples))
                        }
                        val fenceWaitSamples = metrics.mapNotNull { it.vulkanFenceWaitUs }
                        if (fenceWaitSamples.isNotEmpty()) {
                            appendLine(EngineDiagnosticsMetrics.timingSummary("vkFenceWait", fenceWaitSamples))
                        }
                        appendLine(EngineDiagnosticsMetrics.timingSummary("backendSyncWait", metrics.map { it.backendSyncWaitUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("tokenPiece", metrics.map { it.pieceUs }))
                        appendLine(EngineDiagnosticsMetrics.timingSummary("nativeStep", metrics.map { it.totalUs }))
                        appendLine("Token-Schritte (index/sampleUs/decodeUs/submitUs/vkQueueSubmitUs/vkFenceWaitUs/backendSyncWaitUs/pieceUs/totalUs):")
                        val orderedMetrics = metrics.sortedBy { it.index }
                        val representativeMetrics = if (orderedMetrics.size <= 8) {
                            orderedMetrics
                        } else {
                            (orderedMetrics.take(4) + orderedMetrics.takeLast(4)).distinctBy { it.index }
                        }
                        representativeMetrics.forEach { metric ->
                            appendLine(
                                "${metric.index}/${metric.sampleUs}/${metric.decodeUs}/${metric.decodeSubmitUs}/" +
                                    "${metric.vulkanQueueSubmitUs ?: "n/a"}/${metric.vulkanFenceWaitUs ?: "n/a"}/" +
                                    "${metric.backendSyncWaitUs}/${metric.pieceUs}/${metric.totalUs}"
                            )
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
        val latestModelArchitecture = kotlinLines
            .lastOrNull { eventName(it) == "MODEL_IDENTITY" }
            ?.let { field(it, "architecture") }
        val latestGdnPolicy = qwen35GdnPolicyEvidence.lastOrNull()
            ?.substringAfter("LOCAL_LLM_QWEN35_GDN_POLICY - ")
            ?.replace("\\n", "")
            ?.trim()
            ?: "n/a (no GDN policy event; architecture=${latestModelArchitecture ?: "unknown"})"
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
        } ?: "graphSplits=n/a,graphNodes=n/a,cpuNodes=n/a,vulkanNodes=n/a,otherNodes=n/a (graph distribution not recorded)"
        val correctnessGateEvents = kotlinLines
            .filter { eventName(it) == "CORRECTNESS_GATE_RESULT" }
        val configSmokeResults = kotlinLines
            .filter { eventName(it) == "CONFIG_SMOKE_RESULT" }
        val gateStatusSummary = buildList {
            correctnessGateEvents.groupingBy { field(it, "status") ?: "UNKNOWN" }
                .eachCount().toSortedMap()
                .forEach { (status, count) -> add("$status=$count") }
            val smokePasses = configSmokeResults.count {
                field(it, "status")?.equals("PASS", ignoreCase = true)
                    ?: (field(it, "exactOutput") == "true" && field(it, "normalStop") == "true")
            }
            val smokeUnavailable = configSmokeResults.count {
                field(it, "status")?.equals("UNAVAILABLE", ignoreCase = true) == true
            }
            val smokeFailures = configSmokeResults.size - smokePasses - smokeUnavailable
            if (configSmokeResults.isNotEmpty()) {
                add("CONFIG_SMOKE_PASS=$smokePasses")
                add("CONFIG_SMOKE_FAIL=$smokeFailures")
                if (smokeUnavailable > 0) add("CONFIG_SMOKE_UNAVAILABLE=$smokeUnavailable")
            }
            val outputGateResults = kotlinLines.filter { eventName(it) == "OUTPUT_GATE_RESULT" }
            outputGateResults.groupingBy { field(it, "status") ?: "UNKNOWN" }
                .eachCount().toSortedMap()
                .forEach { (status, count) -> add("OUTPUT_GATE_$status=$count") }
        }

        return buildString {
            appendLine("LOCAL LLM ENGINE – DIAGNOSEBERICHT")
            appendLine("============================================================")
            appendLine("Erstellt: ${Date()}")
            appendLine("Export-Schema: 2")
            appendLine("Build: variant=${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"},textDiagnostics=${BuildConfig.TEXT_DIAGNOSTICS_ENABLED},vulkanProfiler=${BuildConfig.VULKAN_PERF_PROFILER_ENABLED}")
            appendLine("Datenbasis: ${ordered.size} Logeinträge · ${samples.size} RAM-Messpunkte")
            appendLine("Export: kompakt, nach Themen gruppiert, mit begrenzten run-korrelierten Text-/Token-Traces im Debug-Diagnosemodus")
            appendLine("------------------------------------------------------------")
            appendLine("Zeitraum: $periodStart bis $periodEnd")
            appendLine("gdnPolicy=$latestGdnPolicy")
            appendLine("graphBackend=$graphSummary")
            appendLine("Gate-Status:")
            if (gateStatusSummary.isEmpty()) appendLine("Keine Korrektheits-Gate-Ergebnisse vorhanden.")
            else gateStatusSummary.forEach(::appendLine)
            appendLine()
            appendLine("[GENERIERTE LLM-AUSGABEN / EXPLIZITE TESTTEXTE / TOKEN-SELECTION]")
            if (textEvidence.isEmpty()) appendLine("Keine gespeicherten Antwort-/Testtext-/Token-Selection-Ereignisse vorhanden.")
            textEvidence.forEach(::appendLine)
            appendLine()
            appendLine("[NATIVE PROMPT-/TEMPLATE-/TOKEN-TRACES]")
            when {
                !BuildConfig.TEXT_DIAGNOSTICS_ENABLED -> appendLine("Nicht aktiviert: Debug-APK mit Textdiagnostik bauen; Release-Builds deaktivieren diese Traces.")
                nativeTextEvidence.isEmpty() -> appendLine("Aktiviert, aber für die letzten Inferenzläufe wurden keine nativen Text-Traces gefunden.")
                else -> nativeTextEvidence.forEach(::appendLine)
            }
            appendLine()
            appendLine("[INFERENZ-KONTEXT / TEMPLATE / TOKENIZER / SAMPLER]")
            if (inferenceSetupEvidence.isEmpty()) appendLine("Keine nativen Prompt-/Sampler-Metadaten vorhanden.")
            inferenceSetupEvidence.forEach { appendLine(readableNativeLine(it, 1_200)) }
            appendLine()
            appendLine("[1] CPU/VULKAN-ABNAHMETEST UND ÄLTERE MATRIX – PROFILERGEBNISSE")
            if (fullMatrixRows.isEmpty()) {
                appendLine("Keine Matrix-Profilzeilen vorhanden.")
            } else {
                fullMatrixRows.forEach { appendLine(readableEventLine(it, 620)) }
            }
            fullMatrixLifecycleEvents.forEach { appendLine(readableEventLine(it, 480)) }
            if (correctnessGateFailures.isNotEmpty()) {
                appendLine("Zusätzliche Korrektheits-Gate-Fehlerdetails:")
                correctnessGateFailures.forEach { appendLine(readableEventLine(it, 520)) }
            }
            if (standaloneCorrectnessGatePasses.isNotEmpty()) {
                appendLine("Zusätzliche Korrektheits-Gate-Nachweise ohne zugehörige Matrix-/Abnahmeresultzeile:")
                standaloneCorrectnessGatePasses.forEach { appendLine(readableEventLine(it, 520)) }
            }
            appendLine()
            appendLine("[2] VULKAN-LAUFZEIT – TREIBER, LAYER-OFFLOAD, Q4_K-/DMMV-KERNELPFAD UND SPEICHERBUDGET")
            if (nativePriorityEvidence.isEmpty()) {
                appendLine("Keine passende Runtime-Zeile für Mali-Treiberfähigkeiten oder tatsächliche Layer-Zuweisung im nativen Log gefunden.")
            } else {
                nativePriorityEvidence.map(::readableNativeLine).forEach(::appendLine)
            }
            appendLine()
            appendLine("[3] VULKAN-GPU-OPERATIONSPROFIL – LETZTE ${MAX_VULKAN_PROFILE_SNAPSHOTS} GRAPH-SNAPSHOTS")
            vulkanOperationSummary.lineSequence().forEach { appendLine(it) }
            appendLine()
            appendLine("[4] BACKEND- UND INFERENZ-MESSWERTE")
            if (performanceEvents.isEmpty()) {
                appendLine("Keine abgeschlossenen Backend-/Inferenz-Messwerte gefunden.")
            }
            performanceEvents.forEach(::appendLine)
            appendLine()
            appendLine("[5] WICHTIGE EREIGNISSE / FEHLER")
            if (events.isEmpty()) appendLine("Keine passenden Ereignisse.")
            events.forEach(::appendLine)
            appendLine()
            // Keep the most valuable throughput and synchronization evidence ahead
            // of the potentially unbounded number of per-PID/per-phase RAM groups.
            appendLine("[6] TOKEN-DECODE- UND SYNCHRONISATIONSZEITEN")
            tokenMetricSummary.lineSequence().forEach { appendLine(it) }
            appendLine()
            appendLine("[7] RAM-ZUSAMMENFASSUNG")
            if (memorySummaries.isEmpty()) appendLine("Keine RAM-Samples gefunden.")
            memorySummaries.forEach(::appendLine)
            appendLine()
            appendLine("[8] RAM-ZEITVERLAUF")
            memoryTimeline.forEach(::appendLine)
            appendLine()
            appendLine("[DIAGNOSTIK-VOLLSTÄNDIGKEIT]")
            appendLine("Debug-Textdiagnostik=${BuildConfig.TEXT_DIAGNOSTICS_ENABLED}; Antworttext wird run-korreliert exportiert; native Eingaben/gerenderte Prompts/Token-IDs und Stichproben der Token-Pieces erscheinen nur bei aktivierter Debug-Textdiagnostik.")
            appendLine("Export-Schema 2 kennzeichnet diesen Export eindeutig; ältere Exporte ohne Schema-Kennung enthalten die vollständigen Text-Traces nicht.")
            appendLine()
            appendLine("[9] NATIVE / VULKAN – AUSGEWÄHLTE EREIGNISSE")
            if (nativeSelected.isEmpty()) appendLine("Keine passenden nativen Backend-Meldungen gefunden.")
            nativeSelected.map(::readableNativeLine).forEach(::appendLine)
            appendLine()
            appendLine("Hinweis: Im Debug-Diagnosemodus sind runId-korrelierte Eingaben, gerenderte Templates, Token-Piece-Stichproben und generierte Modellausgaben begrenzt enthalten. Release-Builds exportieren keine Rohtextdiagnostik.")
        }
        .let { limitUtf8(it, MAX_EXPORT_BYTES) }
    }

    private fun limitUtf8(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        val suffix = "\n[Export begrenzt: weitere Einträge wurden abgeschnitten]\n"
        val limit = (maxBytes - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        var end = limit.coerceAtMost(bytes.size)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + suffix
    }
}
