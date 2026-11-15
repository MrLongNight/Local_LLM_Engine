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
 * Owns diagnostic log persistence and cross-process file access.
 *
 * Parsing/report composition stays in [EngineDiagnostics]. Each process writes
 * a separate bounded file so the main process can export service events after
 * the :inference process exits.
 */
internal object EngineDiagnosticsStorage {
    private const val TAG = "EngineDiagnostics"
    private const val MAX_LOG_ENTRIES = 500
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val persistLock = Any()

    @Volatile
    private var processLogFile: File? = null

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
        // Throwable messages may contain embedded stack/callbackFlow newlines. Keep each
        // persisted diagnostic row atomic so it cannot corrupt parsing of later headers.
        val oneLineDetails = details
            .replace("\r\n", "\\n")
            .replace('\r', ' ')
            .replace("\n", "\\n")
        return if (oneLineDetails.isNotBlank()) {
            "$timestamp [$level] [$tag] $event - $oneLineDetails"
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
            .distinct()
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
