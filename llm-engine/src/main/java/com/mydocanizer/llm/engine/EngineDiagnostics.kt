package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded app-private diagnostics. Never pass prompts, generated responses, credentials,
 * model paths, Content URIs or remote URLs as event details.
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
            runCatching {
                val target = ensureDirectoryLocked(context.applicationContext)
                appendLocked(
                    "INFO",
                    "diagnostics",
                    "SESSION_STARTED",
                    "apiLevel=${Build.VERSION.SDK_INT};device=${Build.MANUFACTURER} ${Build.MODEL};abis=${Build.SUPPORTED_ABIS.take(4).joinToString(",")};appVersion=${appVersion(context.applicationContext)}"
                )
                target
            }.onFailure {
                Log.e("EngineDiagnostics", "Could not initialize diagnostics storage", it)
            }
        }
    }

    fun info(component: String, event: String, details: String? = null) =
        write("INFO", component, event, details)

    fun warn(component: String, event: String, details: String? = null) =
        write("WARN", component, event, details)

    fun error(component: String, event: String, error: Throwable, details: String? = null) {
        val stack = Log.getStackTraceString(error)
        val summary = "${error.javaClass.name}: ${error.message.orEmpty()}"
        val combined = listOfNotNull(
            details,
            summary,
            stack.takeIf { it.isNotBlank() }?.let { "stack=$it" }
        ).joinToString(" | ")
        write("ERROR", component, event, combined)
    }

    private fun write(level: String, component: String, event: String, details: String?) {
        synchronized(lock) {
            if (directory == null) return
            appendLocked(level, component, event, details)
        }
    }

    private fun ensureDirectoryLocked(context: Context): File {
        val target = File(context.filesDir, LOG_DIRECTORY)
        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
            throw IOException("Could not create diagnostics directory")
        }
        directory = target
        return target
    }

    private fun appendLocked(level: String, component: String, event: String, details: String?) {
        val targetDirectory = directory ?: return
        runCatching {
            val current = File(targetDirectory, CURRENT_FILE)
            val previous = File(targetDirectory, PREVIOUS_FILE)
            val encoded = DiagnosticLogFormatter.formatLine(
                utcNow(), level, component, event, details
            ).toByteArray(Charsets.UTF_8)

            if (current.exists() && current.length() + encoded.size > MAX_FILE_BYTES) {
                if (previous.exists() && !previous.delete()) {
                    throw IOException("Could not remove previous diagnostics log during rotation")
                }
                if (!current.renameTo(previous)) {
                    throw IOException("Could not rotate current diagnostics log")
                }
            }
            FileOutputStream(current, true).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
        }.onFailure {
            Log.e("EngineDiagnostics", "Could not append diagnostic event: $event", it)
        }
    }

    /**
     * Exports a consistent snapshot of both bounded log files through SAF.
     * Call from Dispatchers.IO. The returned byte count excludes the export header/footer.
     */
    fun exportTo(context: Context, destination: Uri): Result<Long> {
        return try {
            val appContext = context.applicationContext
            val snapshot = synchronized(lock) {
                val target = ensureDirectoryLocked(appContext)
                appendLocked("INFO", "diagnostics", "EXPORT_STARTED", null)
                DiagnosticLogSnapshotter.capture(
                    File(target, PREVIOUS_FILE),
                    File(target, CURRENT_FILE)
                )
            }

            val output = appContext.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Could not open selected export destination")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("Local LLM Engine diagnostics")
                writer.appendLine("Exported UTC: ${utcNow()}")
                writer.appendLine("Format: timestamp, level, component, event, details (tab-separated)")
                writer.appendLine("Privacy: chat prompts and generated responses are not recorded")
                writer.appendLine()
                if (snapshot.previous.isNotEmpty()) {
                    writer.write(String(snapshot.previous, Charsets.UTF_8))
                }
                if (snapshot.current.isNotEmpty()) {
                    writer.write(String(snapshot.current, Charsets.UTF_8))
                }
                writer.appendLine(
                    DiagnosticLogFormatter.formatLine(
                        utcNow(), "INFO", "diagnostics", "EXPORT_SNAPSHOT_COMPLETED",
                        "logBytes=${snapshot.totalBytes}"
                    ).trimEnd()
                )
                writer.flush()
            }

            synchronized(lock) {
                appendLocked(
                    "INFO", "diagnostics", "EXPORT_COMPLETED",
                    "logBytes=${snapshot.totalBytes}"
                )
            }
            Result.success(snapshot.totalBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            synchronized(lock) {
                appendLocked(
                    "ERROR", "diagnostics", "EXPORT_FAILED",
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
            Result.failure(error)
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}

/** Immutable export snapshot captured while the writer lock is held. */
internal data class DiagnosticLogSnapshot(
    val previous: ByteArray,
    val current: ByteArray
) {
    val totalBytes: Long
        get() = previous.size.toLong() + current.size.toLong()
}

/** File snapshot helper separated for deterministic unit testing. */
internal object DiagnosticLogSnapshotter {
    fun capture(previous: File, current: File): DiagnosticLogSnapshot =
        DiagnosticLogSnapshot(
            previous = previous.takeIf { it.isFile }?.readBytes() ?: ByteArray(0),
            current = current.takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
        )
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
        return fields.joinToString(separator = "\\t", postfix = "\\n") { sanitize(it) }
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
