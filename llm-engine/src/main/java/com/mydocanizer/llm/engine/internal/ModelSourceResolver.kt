package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object ModelSourceResolver {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    suspend fun resolveToFile(context: Context, source: ModelSource): File =
        withContext(Dispatchers.IO) {
            val targetDir = File(context.filesDir, "models")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IllegalStateException("Could not create private model directory")
            }
            require(targetDir.isDirectory && targetDir.canWrite()) {
                "Private model directory is not writable"
            }

            when (source) {
                is ModelSource.FilePath -> {
                    val file = source.file
                    require(file.isFile && file.canRead()) {
                        "Model file does not exist or cannot be read: ${source.path}"
                    }
                    validateGgufMagic(file)
                    file
                }

                is ModelSource.ContentUri -> {
                    val uri = Uri.parse(source.uriString)
                    val displayName = source.displayName
                        ?: queryFileName(context, uri)
                        ?: "imported_model.gguf"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Could not open Content URI: ${source.uriString}")
                    copyAndPublish(targetDir, displayName, input)
                }

                is ModelSource.AssetPath -> {
                    require(source.assetName.isNotBlank()) { "Asset name must not be blank" }
                    val input = context.assets.open(source.assetName)
                    copyAndPublish(targetDir, source.assetName, input)
                }

                is ModelSource.RemoteUrl -> downloadRemoteFile(targetDir, source)
            }
        }

    private suspend fun downloadRemoteFile(targetDir: File, source: ModelSource.RemoteUrl): File {
        var currentUrl = URL(source.url)
        require(currentUrl.protocol.equals("https", ignoreCase = true)) {
            "Remote model downloads require HTTPS"
        }
        require(currentUrl.userInfo == null) {
            "Credentials embedded in remote model URLs are not supported"
        }

        val fallbackName = source.suggestedFileName
            ?: currentUrl.path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "downloaded_model.gguf"

        for (redirectCount in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val connection = currentUrl.openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("Remote model URL must use HTTPS")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Local-LMM-Engine/1.0")

            try {
                val status = connection.responseCode
                if (status in setOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) {
                        "HTTPS model server returned a redirect without Location"
                    }
                    require(redirectCount < MAX_REDIRECTS) {
                        "Remote model download exceeded $MAX_REDIRECTS redirects"
                    }
                    val nextUrl = URL(currentUrl, location)
                    require(nextUrl.protocol.equals("https", ignoreCase = true)) {
                        "Remote model redirect attempted to downgrade from HTTPS"
                    }
                    require(nextUrl.userInfo == null) {
                        "Credentials embedded in remote model redirects are not supported"
                    }
                    currentUrl = nextUrl
                    continue
                }

                require(status in 200..299) {
                    "Remote model download failed with HTTP status $status"
                }
                return copyAndPublish(targetDir, fallbackName, connection.inputStream)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Remote model download did not complete")
    }

    /**
     * Streams a source into a private .part file, verifies its GGUF header, hashes it,
     * then publishes it with an atomic same-filesystem rename. A failed/cancelled copy
     * can therefore never appear as a selectable .gguf model.
     */
    private suspend fun copyAndPublish(
        targetDir: File,
        displayName: String,
        input: InputStream
    ): File {
        val coroutineContext = currentCoroutineContext()
        var temporaryFile: File? = null
        try {
            var sha256: String? = null
            input.use { source ->
                val digest = MessageDigest.getInstance("SHA-256")
                val stage = File.createTempFile(".model-import-", ".part", targetDir)
                temporaryFile = stage
                FileOutputStream(stage).use { destination ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        destination.write(buffer, 0, count)
                    }
                    destination.fd.sync()
                }
                coroutineContext.ensureActive()
                validateGgufMagic(stage)
                sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            }

            coroutineContext.ensureActive()
            val verifiedHash = checkNotNull(sha256)
            val stage = checkNotNull(temporaryFile)
            val safeName = safeModelFileName(displayName)
            val destination = File(targetDir, "$verifiedHash-$safeName")

            // POSIX rename is atomic within the private models directory. It also safely
            // replaces a previous copy with the same content hash.
            Os.rename(stage.absolutePath, destination.absolutePath)
            return destination
        } finally {
            temporaryFile?.takeIf { it.exists() }?.delete()
        }
    }

    internal fun validateGgufMagic(file: File) {
        require(file.isFile && file.canRead()) { "GGUF source is not a readable file" }
        val header = ByteArray(GGUF_MAGIC.size)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            require(offset == GGUF_MAGIC.size && header.contentEquals(GGUF_MAGIC)) {
                "Invalid GGUF file: expected GGUF magic bytes at offset 0"
            }
        }
    }

    private fun safeModelFileName(candidate: String): String {
        val leaf = candidate.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = leaf
            .replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', '_', ' ')
            .take(120)
            .ifBlank { "model" }
        return if (cleaned.endsWith(".gguf", ignoreCase = true)) cleaned else "$cleaned.gguf"
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {
                // A provider may not expose OpenableColumns; use the URI segment below.
            }
        }
        return uri.lastPathSegment
    }
}
