package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.util.Log
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

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(tag: String, event: String, details: String = "") {
        val entry = formatLog("INFO", tag, event, details)
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
        Log.i(TAG, "[$tag] $event: $details")
    }

    fun error(tag: String, event: String, throwable: Throwable? = null) {
        val details = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val entry = formatLog("ERROR", tag, event, details)
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
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

    fun exportTo(context: Context, destination: Uri): Result<Long> {
        return runCatching {
            val content = getLogs().joinToString(separator = "\n")
            val bytes = content.toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(destination)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            } ?: throw IllegalStateException("Could not open output stream for destination URI")
            bytes.size.toLong()
        }
    }
}
