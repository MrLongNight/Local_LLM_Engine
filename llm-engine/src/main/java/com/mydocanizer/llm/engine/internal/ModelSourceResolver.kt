package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.GgufHeaderValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Imports model sources into app-private storage using staged, SHA-256-addressed files. */
internal object ModelSourceResolver {
    suspend fun resolveToFile(context: Context, source: ModelSource): File = withContext(Dispatchers.IO) {
        EngineDiagnostics.info("model-source", "RESOLUTION_STARTED", "sourceType=${source.javaClass.simpleName}")
        try {
        val file = when (source) {
            is ModelSource.FilePath -> {
                val candidate = source.file
                require(candidate.exists() && candidate.isFile) {
                    "Model file does not exist or is not a regular file: ${source.path}"
                }
                require(candidate.canRead()) { "Cannot read model file at path: ${source.path}" }
                candidate
            }
            is ModelSource.ContentUri -> {
                val uri = Uri.parse(source.uriString)
                val name = source.displayName ?: queryFileName(context, uri) ?: "imported_model.gguf"
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Could not open Content URI: ${source.uriString}")
                importStream(context, input, name)
            }
            is ModelSource.AssetPath -> importStream(
                context, context.assets.open(source.assetName), source.assetName.substringAfterLast('/')
            )
            is ModelSource.RemoteUrl -> downloadRemote(context, source)
        }
        validateGgufHeader(file)
        EngineDiagnostics.info("model-source", "RESOLUTION_SUCCEEDED", "sizeBytes=${file.length()}")
        file
        } catch (error: Throwable) {
            EngineDiagnostics.error("model-source", "RESOLUTION_FAILED", error)
            throw error
        }
    }

    private fun validateGgufHeader(file: File) {
        require(file.isFile && file.length() >= 4L) {
            "Model file is empty or too small to be a GGUF file"
        }
        val magic = ByteArray(4)
        file.inputStream().use { input ->
            val count = input.read(magic)
            require(count == 4 && GgufHeaderValidator.isValid(magic, file.length())) {
                "Invalid model file: GGUF magic header is missing"
            }
        }
    }

    private suspend fun downloadRemote(context: Context, source: ModelSource.RemoteUrl): File {
        var currentUrl = URL(source.url)
        require(currentUrl.protocol.equals("https", ignoreCase = true)) {
            "Remote model URLs must use HTTPS"
        }

        var redirects = 0
        while (true) {
            // Handle redirects ourselves: HttpURLConnection's automatic redirect handling
            // must not silently downgrade a model download from HTTPS to HTTP.
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "LocalLlmEngine/1.0")
            }

            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) {
                        "Model server returned redirect HTTP $status without a Location header"
                    }
                    require(redirects < 5) { "Model download exceeded the maximum of 5 redirects" }
                    val nextUrl = URL(currentUrl, location)
                    require(nextUrl.protocol.equals("https", ignoreCase = true)) {
                        "Model download redirect attempted to leave HTTPS"
                    }
                    currentUrl = nextUrl
                    redirects++
                    continue
                }

                require(status in 200..299) { "Model download failed with HTTP $status" }
                val name = source.suggestedFileName
                    ?: currentUrl.path.substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: "downloaded_model.gguf"
                return importStream(context, connection.inputStream, name)
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Model download did not complete")
    }

    private suspend fun importStream(context: Context, input: java.io.InputStream, displayName: String): File {
        val directory = File(context.filesDir, "models")
        check(directory.exists() || directory.mkdirs()) { "Could not create model storage directory" }
        val safeName = sanitizeModelName(displayName)
        val baseName = safeName.substringBeforeLast('.', safeName).take(72).ifBlank { "model" }
        val temporary = File(directory, ".${UUID.randomUUID()}.part")
        val jobContext = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        try {
            input.use { source ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        jobContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        copiedBytes += count
                    }
                }
            }
            require(copiedBytes > 0L) { "Downloaded/imported model file is empty" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$baseName-$hash.gguf")
            if (destination.exists()) {
                require(destination.length() == copiedBytes) { "SHA-256 filename collision" }
                temporary.delete()
                return destination
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not atomically finalize imported model file")
            }
            return destination
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
    }

    private fun sanitizeModelName(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
            .take(96)
        return leaf.substringBeforeLast('.', leaf).ifBlank { "model" } + ".gguf"
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) return cursor.getString(index)
                    }
                }
            } catch (_: Exception) {
                // Providers may not expose OpenableColumns.
            }
        }
        return uri.lastPathSegment
    }
}
