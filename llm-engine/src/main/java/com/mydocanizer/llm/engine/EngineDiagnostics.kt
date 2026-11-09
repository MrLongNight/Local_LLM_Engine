package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Diagnostic logger for Local LLM Engine lifecycle and inference metrics.
 * Does not store chat prompts or sensitive user text.
 */
object EngineDiagnostics {
    private const val TAG = "EngineDiagnostics"
    private const val MAX_LOG_ENTRIES = 500
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val persistLock = Any()

    @Volatile
    private var processLogFile: File? = null

    /**
     * Each process writes a separate file in shared app-private storage. This makes
     * :inference events exportable from the main process even after a service crash.
     * Prompt and response text are not stored.
     */
    fun initialize(context: Context) {
        val directory = File(context.applicationContext.filesDir, "diagnostics")
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create diagnostics directory: ${directory.absolutePath}")
            return
        }
        processLogFile = File(directory, "engine-${Process.myPid()}.log")
    }

    fun info(tag: String, event: String, details: String = "") {
        val entry = formatLog("INFO", tag, event, details)
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
        persist(entry)
        Log.i(TAG, "[$tag] $event: $details")
    }

    fun error(tag: String, event: String, throwable: Throwable? = null) {
        val details = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val entry = formatLog("ERROR", tag, event, details)
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
        persist(entry)
        Log.e(TAG, "[$tag] $event: $details", throwable)
    }

    private fun formatLog(level: String, tag: String, event: String, details: String): String {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        return if (details.isNotBlank()) {
            "$timestamp [$level] [$tag] $event - $details"
        } else {
            "$timestamp [$level] [$tag] $event"
        }
    }

    fun getLogs(): List<String> = logQueue.toList()

    private fun persist(entry: String) {
        val file = processLogFile ?: return
        synchronized(persistLock) {
            runCatching {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                file.appendText(entry + "\n", Charsets.UTF_8)
                if (file.length() > MAX_LOG_FILE_BYTES) {
                    val recent = file.readLines(Charsets.UTF_8).takeLast(MAX_LOG_ENTRIES * 4)
                    file.writeText(recent.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
                }
            }.onFailure { Log.w(TAG, "Could not persist diagnostic entry", it) }
        }
    }

    private fun readAllProcessLogs(context: Context): List<String> {
        val directory = File(context.applicationContext.filesDir, "diagnostics")
        val persisted = directory.listFiles { file ->
            file.isFile && file.name.startsWith("engine-") && file.name.endsWith(".log")
        }?.sortedBy { it.name }?.flatMap { file ->
            runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        }.orEmpty()
        val persistedEntries = persisted.toHashSet()
        val inMemoryOnly = getLogs().filterNot { it in persistedEntries }
        return (persisted + inMemoryOnly).asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
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
        val availableRamMb: Double?
    )

    private const val MAX_EXPORT_BYTES = 16 * 1024
    private const val MAX_EVENT_LINES = 24
    private const val MAX_NATIVE_LINES = 14
    private const val MAX_MEMORY_TIMELINE_LINES = 8

    private fun field(line: String, key: String): String? {
        val marker = "$key="
        val start = line.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val comma = line.indexOf(',', valueStart).takeIf { it >= 0 } ?: line.length
        val brace = line.indexOf('}', valueStart).takeIf { it >= 0 } ?: line.length
        return line.substring(valueStart, minOf(comma, brace)).takeIf { it.isNotBlank() }
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
            availableRamMb = field(line, "systemAvailableRamMb")?.toDoubleOrNull()
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
            "GENERATION_COMPLETED"
        ) || event.startsWith("BACKEND_BENCHMARK_")
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
        val performanceEvents = kotlinLines
            .filterNot { it.contains("RESOURCE_SNAPSHOT") }
            .filter(::isPerformanceEvent)
            .takeLast(16)
            .map { compactLine(it, 520) }

        val nativeKeywords = listOf(
            "VULKAN", "GGML_VULKAN", "BACKEND", "DEVICE", "SUBGROUP",
            "MEMORY", "ALLOC", "PIPELINE", "SHADER", "FENCE", "QUEUE",
            "FAILED", "ERROR", "WARN", "UNSUPPORTED", "FALLBACK",
            "OFFLOAD", "BUFFER", "LAYER", "OUT OF MEMORY", "DEVICE LOST"
        )
        val relevantNative = nativeLines.filter { line ->
            val upper = line.uppercase(Locale.US)
            nativeKeywords.any(upper::contains)
        }
        val nativeSelected = (
            relevantNative.take(12) + relevantNative.takeLast(MAX_NATIVE_LINES - 12)
        ).distinct().map { compactLine(it, 360) }

        val memorySummaries = samples
            .groupBy { "${it.pid}/${it.phase}" }
            .toSortedMap()
            .map { (group, entries) ->
                fun peak(selector: (ResourceSample) -> Double?): String =
                    entries.mapNotNull(selector).maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
                val minimumAvailable = entries.mapNotNull { it.availableRamMb }.minOrNull()
                    ?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
                val last = entries.lastOrNull()
                val model = last?.let { field(it.line, "model") }
                val context = last?.let { field(it.line, "contextTokens") }
                val batch = last?.let { field(it.line, "batchSize") }
                val layers = last?.let { field(it.line, "gpuLayersRequested") }
                "$group n=${entries.size} PSS_peak=${peak { it.pssMb }}MB " +
                    "nativePSS_peak=${peak { it.nativePssMb }}MB nativeHeap_peak=${peak { it.nativeHeapMb }}MB " +
                    "javaHeap_peak=${peak { it.javaHeapMb }}MB availableRAM_min=${minimumAvailable}MB " +
                    listOfNotNull(model?.let { "model=$it" }, context?.let { "ctx=$it" },
                        batch?.let { "batch=$it" }, layers?.let { "gpuLayers=$it" }).joinToString(" ")
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
                    field(sample.line, "contextTokens")?.let { "ctx=$it" },
                    field(sample.line, "gpuLayersRequested")?.let { "gpuLayers=$it" },
                    field(sample.line, "backendActive")?.let { "backend=$it" }
                ).filterNotNull().joinToString(" ")
                val backend = if (sample.line.contains("backendMemory=")) {
                    " " + sample.line.substringAfter("backendMemory=").take(200)
                } else ""
                "${sample.timestamp} pid=${sample.pid} ${sample.phase} $values$backend"
            }

        return buildString {
            appendLine("LOCAL LLM ENGINE – KOMPAKTER DIAGNOSEBERICHT")
            appendLine("Erstellt: ${Date()}")
            appendLine("Erfasste Logzeilen: ${ordered.size}; RAM-Samples: ${samples.size}")
            appendLine("Zeitraum: ${ordered.firstOrNull()?.substringBefore(" [") ?: "n/a"} bis ${ordered.lastOrNull()?.substringBefore(" [") ?: "n/a"}")
            appendLine()
            appendLine("[WICHTIGE EREIGNISSE]")
            if (events.isEmpty()) appendLine("Keine passenden Ereignisse.")
            events.forEach(::appendLine)
            appendLine()
            appendLine("[BACKEND- UND INFERENZ-MESSWERTE]")
            if (performanceEvents.isEmpty()) {
                appendLine("Keine abgeschlossenen Backend-/Inferenz-Messwerte gefunden.")
            }
            performanceEvents.forEach(::appendLine)
            appendLine()
            appendLine("[RAM-ZUSAMMENFASSUNG – SPITZENWERTE PRO PHASE]")
            if (memorySummaries.isEmpty()) appendLine("Keine RAM-Samples gefunden.")
            memorySummaries.forEach(::appendLine)
            appendLine()
            appendLine("[RAM-ZEITVERLAUF – AUSGEDÜNNT, SPITZEN UND BACKEND-SAMPLES]")
            memoryTimeline.forEach(::appendLine)
            appendLine()
            appendLine("[NATIVE / VULKAN – GEFILTERTE MELDUNGEN]")
            if (nativeSelected.isEmpty()) appendLine("Keine passenden nativen Backend-Meldungen gefunden.")
            nativeSelected.forEach(::appendLine)
            appendLine()
            appendLine("Hinweis: Chat-Prompts und Modellantworten werden nicht exportiert. " +
                "Der Bericht ist auf maximal 16 KiB begrenzt; häufige RAM-Samples wurden zusammengefasst.")
        }.let { limitUtf8(it, MAX_EXPORT_BYTES) }
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

    fun exportTo(context: Context, destination: Uri): Result<Long> {
        return runCatching {
            val content = buildCompactReport(readAllProcessLogs(context))
            val bytes = content.toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(destination)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            } ?: throw IllegalStateException("Could not open output stream for destination URI")
            bytes.size.toLong()
        }
    }
}
