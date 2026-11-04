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
        return (persisted + inMemoryOnly).distinct().sorted().takeLast(MAX_LOG_ENTRIES * 20)
    }

    fun exportTo(context: Context, destination: Uri): Result<Long> {
        return runCatching {
            val content = readAllProcessLogs(context).joinToString(separator = "\n")
            val bytes = content.toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(destination)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            } ?: throw IllegalStateException("Could not open output stream for destination URI")
            bytes.size.toLong()
        }
    }
}
