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
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns diagnostic log persistence and cross-process file access.
 *
 * Each process writes a separate, size-bounded file so the main process can
 * export service events after the :inference process exits. Both process files
 * and the in-memory queue have fixed retention limits, including Debug builds.
 */
internal object EngineDiagnosticsStorage {
    private const val TAG = "EngineDiagnostics"
    private const val MAX_LOG_ENTRIES = 500
    private const val MAX_LOG_FILE_BYTES = 512L * 1024L
    private const val MAX_PROCESS_LOG_FILES = 4

    private val preserveAllDebugDetails: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.TEXT_DIAGNOSTICS_ENABLED

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val eventSequence = AtomicLong()
    private val persistLock = Any()

    @Volatile
    private var processLogFile: File? = null

    fun initialize(context: Context) {
        val directory = File(context.applicationContext.filesDir, "diagnostics")
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create diagnostics directory: ${directory.absolutePath}")
            return
        }
        val currentFile = File(directory, "engine-${Process.myPid()}.log")
        processLogFile = currentFile
        runCatching {
            if (!currentFile.exists()) currentFile.createNewFile()
            currentFile.setLastModified(System.currentTimeMillis())
            trimLogFileIfNeeded(currentFile)
            pruneOldProcessLogs(directory, currentFile)
        }.onFailure { Log.w(TAG, "Could not enforce diagnostic log retention", it) }
    }

    fun info(tag: String, event: String, details: String = "") {
        val chunks = if (preserveAllDebugDetails) details.chunked(3_000).ifEmpty { listOf("") }
        else listOf(details)
        chunks.forEachIndexed { index, chunk ->
            val partEvent = if (chunks.size == 1) event else "${event}_PART_${index + 1}_OF_${chunks.size}"
            val entry = formatLog("INFO", tag, partEvent, chunk)
            logQueue.add(entry)
            while (logQueue.size > MAX_LOG_ENTRIES) logQueue.poll()
            persist(entry)
            Log.i(TAG, "[$tag] $partEvent: $chunk")
        }
    }

    fun error(tag: String, event: String, throwable: Throwable? = null) {
        val details = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val chunks = if (preserveAllDebugDetails) details.chunked(3_000).ifEmpty { listOf("") }
        else listOf(details)
        chunks.forEachIndexed { index, chunk ->
            val partEvent = if (chunks.size == 1) event else "${event}_PART_${index + 1}_OF_${chunks.size}"
            val entry = formatLog("ERROR", tag, partEvent, chunk)
            logQueue.add(entry)
            while (logQueue.size > MAX_LOG_ENTRIES) logQueue.poll()
            persist(entry)
            Log.e(TAG, "[$tag] $partEvent: $chunk", if (index == 0) throwable else null)
        }
    }

    private fun formatLog(level: String, tag: String, event: String, details: String): String {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        return DiagnosticEventEnvelope.format(
            timestamp = timestamp,
            level = level,
            tag = tag,
            event = event,
            details = details,
            pid = Process.myPid(),
            sequence = eventSequence.incrementAndGet()
        )
    }

    fun getLogs(): List<String> = logQueue.toList()

    private fun persist(entry: String) {
        val file = processLogFile ?: return
        synchronized(persistLock) {
            runCatching {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                file.appendText(entry + "\n", Charsets.UTF_8)
                trimLogFileIfNeeded(file)
            }.onFailure { Log.w(TAG, "Could not persist diagnostic entry", it) }
        }
    }

    private fun trimLogFileIfNeeded(file: File) {
        if (!file.isFile || file.length() <= MAX_LOG_FILE_BYTES) return

        val bytes = file.readBytes()
        var start = (bytes.size - MAX_LOG_FILE_BYTES.toInt()).coerceAtLeast(0)
        // Start at a complete line boundary; never keep a partial UTF-8/log envelope.
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        if (start < bytes.size) start++

        val tail = if (start < bytes.size) {
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        } else {
            ""
        }
        val retained = tail.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_LOG_ENTRIES)
            .joinToString(separator = "\n", postfix = "\n")
        file.writeText(retained, Charsets.UTF_8)
    }

    private fun pruneOldProcessLogs(directory: File, currentFile: File) {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith("engine-") && file.name.endsWith(".log")
        } ?: return
        val retained = files
            .sortedByDescending { it.lastModified() }
            .take(MAX_PROCESS_LOG_FILES)
            .toMutableSet()
        retained += currentFile
        files.filterNot { it in retained }.forEach { stale ->
            if (!stale.delete()) {
                Log.w(TAG, "Could not remove stale diagnostic process log: ${stale.name}")
            }
        }
        // Also clean files created by earlier, unbounded diagnostic builds. Merely
        // limiting future appends would otherwise leave multi-megabyte legacy logs
        // in the next export.
        retained.forEach(::trimLogFileIfNeeded)
    }

    internal fun diagnosticsDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "diagnostics")

    internal fun processLogFiles(context: Context): List<File> {
        val directory = diagnosticsDirectory(context)
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith("engine-") && file.name.endsWith(".log")
        }
        if (files == null) {
            Log.e(TAG, "Could not enumerate diagnostic source directory: ${directory.absolutePath}")
            return emptyList()
        }
        return files.sortedBy { it.name }
    }

    internal fun readAllProcessLogs(context: Context): List<String> {
        val persisted = processLogFiles(context).flatMap { file ->
            runCatching { file.readLines(Charsets.UTF_8) }
                .onFailure { Log.e(TAG, "Could not read diagnostic source file: ${file.name}", it) }
                .getOrDefault(emptyList())
        }
        val persistedEntries = persisted.toHashSet()
        val inMemoryOnly = getLogs().filterNot { it in persistedEntries }
        return (persisted + inMemoryOnly).asSequence()
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
    }

    /**
     * Writes with explicit truncate semantics and reads the provider URI back.
     * A successful export must never be reported for a zero-byte or partial file.
     */
    internal fun writeAndVerifyExport(context: Context, destination: Uri, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Es wurden keine Diagnosebytes zum Export übergeben." }
        val resolver = context.contentResolver
        val descriptor = runCatching {
            resolver.openFileDescriptor(destination, "rwt")
        }.getOrNull()

        if (descriptor != null) {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        } else {
            val outputStream = resolver.openOutputStream(destination, "wt")
                ?: throw IllegalStateException(
                    "Der Android-Dokumentanbieter hat keinen Schreibstream für das ausgewählte Ziel geöffnet."
                )
            outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }

        val writtenBytes = resolver.openInputStream(destination)?.use { it.readBytes() }
            ?: throw IllegalStateException(
                "Der Android-Dokumentanbieter konnte die exportierte Datei nicht zur Prüfung zurücklesen."
            )
        check(writtenBytes.isNotEmpty()) {
            "Der Android-Dokumentanbieter hat eine leere Exportdatei zurückgegeben."
        }
        check(writtenBytes.contentEquals(bytes)) {
            "Die Exportdatei ist unvollständig oder wurde vom Dokumentanbieter verändert " +
                "(erwartet=${bytes.size} Bytes, gelesen=${writtenBytes.size} Bytes)."
        }
    }
}
