package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded, app-private diagnostic log for engine lifecycle, model loading and inference.
 *
 * Prompts, generated responses, model paths, content URIs and remote URLs must never be
 * passed as event details. The formatter additionally redacts common URI/path patterns.
 */
object EngineDiagnostics {
    private const val LOG_DIRECTORY = "diagnostics"
    private const val CURRENT_FILE = "engine-diagnostics.log"
    private const val PREVIOUS_FILE = "engine-diagnostics.previous.log"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private val lock = Any()

    @Volatile
    private var directory: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext
            val target = File(appContext.filesDir, LOG_DIRECTORY)
            if (!target.exists() && !target.mkdirs()) {
                Log.e("EngineDiagnostics", "Could not create diagnostics directory")
                return
            }
            directory = target
            appendLocked(
                "INFO",
                "diagnostics",
                "SESSION_STARTED",
                "apiLevel=${Build.VERSION.SDK_INT};device=${Build.MANUFACTURER} ${Build.MODEL};abis=${Build.SUPPORTED_ABIS.take(4).joinToString(",")};appVersion=${appVersion(appContext)}"
            )
        }
    }

    fun info(component: String, event: String, details: String? = null) =
        write("INFO", component, event, details)

    fun warn(component: String, event: String, details: String? = null) =
        write("WARN", component, event, details)

    fun error(component: String, event: String, error: Throwable, details: String? = null) {
        val stack = Log.getStackTraceString(error)
        val summary = "${error.javaClass.name}: ${error.message.orEmpty()}"
        val combined = listOfNotNull(details, summary, stack.takeIf { it.isNotBlank() }?.let { "stack=$it" })
            .joinToString(" | ")
        write("ERROR", component, event, combined)
    }

    private fun write(level: String, component: String, event: String, details: String?) {
        synchronized(lock) {
            if (directory == null) return
            appendLocked(level, component, event, details)
        }
    }

    private fun appendLocked(level: String, component: String, event: String, details: String?) {
        val targetDirectory = directory ?: return
        runCatching {
            val current = File(targetDirectory, CURRENT_FILE)
            val previous = File(targetDirectory, PREVIOUS_FILE)
            val timestamp = utcNow()
            val encoded = DiagnosticLogFormatter.formatLine(timestamp, level, component, event, details)
                .toByteArray(Charsets.UTF_8)

            if (current.exists() && current.length() + encoded.size > MAX_FILE_BYTES) {
                previous.delete()
                if (!current.renameTo(previous)) {
                    Log.w("EngineDiagnostics", "Could not rotate diagnostics log")
                }
            }
            FileOutputStream(current, true).use { output ->
                output.write(encoded)
                output.flush()
            }
        }.onFailure {
            Log.e("EngineDiagnostics", "Could not append diagnostic event", it)
        }
    }

    /**
     * Exports the previous and current log through SAF. Call from an IO dispatcher.
     * The returned number is the number of exported log bytes, excluding the small header.
     */
    fun exportTo(context: Context, destination: Uri): Result<Long> = runCatching {
        val appContext = context.applicationContext
        initialize(appContext)
        info("diagnostics", "EXPORT_STARTED")
        val current = File(appContext.filesDir, "$LOG_DIRECTORY/$CURRENT_FILE")
        val previous = File(appContext.filesDir, "$LOG_DIRECTORY/$PREVIOUS_FILE")
        val totalBytes = listOf(previous, current).filter { it.isFile }.sumOf { it.length() }

        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw IllegalStateException("Could not open selected export destination")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Local LLM Engine diagnostics")
            writer.appendLine("Exported UTC: ${utcNow()}")
            writer.appendLine("Format: timestamp, level, component, event, details (tab-separated)")
            writer.appendLine("Privacy: chat prompts and generated responses are not recorded")
            writer.appendLine()
            listOf(previous, current).filter { it.isFile }.forEach { file ->
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> writer.appendLine(line) }
                }
            }
            writer.appendLine(DiagnosticLogFormatter.formatLine(utcNow(), "INFO", "diagnostics", "EXPORT_SNAPSHOT_COMPLETED", null))
            writer.flush()
        }
        info("diagnostics", "EXPORT_COMPLETED", "logBytes=$totalBytes")
        totalBytes
    }

    private fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}

/** Pure formatter shared by the diagnostics writer and JVM tests. */
internal object DiagnosticLogFormatter {
    private val urlPattern = Regex("(?i)https?://[^\\s]+")
    private val contentUriPattern = Regex("(?i)content://[^\\s]+")
    private val localPathPattern = Regex("(?<![A-Za-z0-9])/(?:data|storage|sdcard|home|Users)/[^\\s,;]+")

    fun formatLine(
        timestampUtc: String,
        level: String,
        component: String,
        event: String,
        details: String?
    ): String {
        val fields = listOf(timestampUtc, level, component, event, details.orEmpty())
        return fields.joinToString(separator = "\t", postfix = "\n") { sanitize(it) }
    }

    fun sanitize(value: String): String = value
        .replace(urlPattern, "<url-redacted>")
        .replace(contentUriPattern, "<content-uri-redacted>")
        .replace(localPathPattern, "<local-path-redacted>")
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .take(2500)
}

/** Minimal GGUF magic/length check, extracted so the boundary can be unit tested. */
internal object GgufHeaderValidator {
    fun isValid(header: ByteArray, fileLength: Long): Boolean {
        if (fileLength < 4L || header.size < 4) return false
        return header[0] == 0x47.toByte() &&
            header[1] == 0x47.toByte() &&
            header[2] == 0x55.toByte() &&
            header[3] == 0x46.toByte()
    }
}

/** Converts llama.cpp's native terminal codes into the stable public result contract. */
internal fun finishReasonFromNative(rawReason: Int, cancellationRequested: Boolean): FinishReason =
    when {
        cancellationRequested || rawReason == 2 -> FinishReason.CANCELLED
        rawReason == 0 -> FinishReason.STOP
        rawReason == 1 -> FinishReason.MAX_TOKENS
        else -> FinishReason.ERROR
    }
