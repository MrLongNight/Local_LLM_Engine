package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
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
    private val PROC_SELF_FD_PATH = Regex("^/proc/self/fd/([0-9]+)$")

    suspend fun resolveToFile(context: Context, source: ModelSource): File =
        withContext(Dispatchers.IO) {
            when (source) {
                is ModelSource.FilePath -> {
                    val file = source.file
                    if (PROC_SELF_FD_PATH.matches(source.path)) {
                        // /proc/self/fd/N is a process-local descriptor path, not a
                        // conventional filesystem path. File.isFile/canRead() are not
                        // reliable for Binder-transferred SAF descriptors on Android.
                        // Validate the actual descriptor with fstat/lseek/pread/mmap
                        // and keep the original descriptor open for llama.cpp.
                        validateSeekableMmapDescriptor(source.path)
                    } else {
                        require(file.isFile && file.canRead()) {
                            "Model file does not exist or cannot be read: ${source.path}"
                        }
                        validateGgufMagic(file)
                    }
                    file
                }

                is ModelSource.ContentUri -> {
                    val targetDir = ensureModelDirectory(context)
                    val uri = Uri.parse(source.uriString)
                    val displayName = source.displayName
                        ?: queryFileName(context, uri)
                        ?: "imported_model.gguf"
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Could not open Content URI: ${source.uriString}")
                    copyAndPublish(targetDir, displayName, input)
                }

                is ModelSource.AssetPath -> {
                    val targetDir = ensureModelDirectory(context)
                    require(source.assetName.isNotBlank()) { "Asset name must not be blank" }
                    val input = context.assets.open(source.assetName)
                    copyAndPublish(targetDir, source.assetName, input)
                }

                is ModelSource.RemoteUrl -> downloadRemoteFile(ensureModelDirectory(context), source)
            }
        }

    private fun ensureModelDirectory(context: Context): File {
        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("Could not create private model directory")
        }
        require(targetDir.isDirectory && targetDir.canWrite()) {
            "Private model directory is not writable"
        }
        return targetDir
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

    /**
     * Validates a descriptor path inside the current process without copying its contents.
     *
     * SAF providers may return regular file descriptors or pipe-backed descriptors.
     * llama.cpp needs random access and mmap, so only a readable, seekable regular file
     * is accepted. All checks operate on a temporary duplicate opened through procfs;
     * the ParcelFileDescriptor owned by InferenceService remains open for native loading.
     */
    private fun validateSeekableMmapDescriptor(procPath: String) {
        val descriptorNumber = PROC_SELF_FD_PATH.matchEntire(procPath)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid process-local model descriptor path")

        val stat = try {
            Os.stat(procPath)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "SAF model descriptor $descriptorNumber is no longer available in :inference; " +
                    "the provider descriptor may have been closed before model loading",
                error
            )
        }

        val fileType = stat.st_mode and OsConstants.S_IFMT
        if (fileType != OsConstants.S_IFREG) {
            val sourceType = when (fileType) {
                OsConstants.S_IFIFO -> "pipe"
                OsConstants.S_IFSOCK -> "socket"
                OsConstants.S_IFCHR -> "character device"
                OsConstants.S_IFBLK -> "block device"
                OsConstants.S_IFDIR -> "directory"
                else -> "non-regular source (mode=0x${stat.st_mode.toString(16)})"
            }
            throw IllegalArgumentException(
                "Selected SAF provider supplied a $sourceType descriptor, not a seekable regular file. " +
                    "llama.cpp requires a seekable, mmap-capable file descriptor. " +
                    "The source was not copied; choose a provider that exposes the file directly."
            )
        }

        require(stat.st_size >= GGUF_MAGIC.size) {
            "SAF model descriptor $descriptorNumber points to a file smaller than a GGUF header"
        }

        val probeFd = try {
            Os.open(
                procPath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0
            )
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "SAF model descriptor $descriptorNumber cannot be reopened read-only in :inference",
                error
            )
        }

        try {
            val openedStat = Os.fstat(probeFd)
            require((openedStat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG) {
                "SAF model descriptor changed type during validation; expected a regular file"
            }
            require(openedStat.st_size >= GGUF_MAGIC.size) {
                "SAF model descriptor became shorter than a GGUF header during validation"
            }

            try {
                Os.lseek(probeFd, 0L, OsConstants.SEEK_CUR)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Selected SAF provider returned a non-seekable descriptor. " +
                        "llama.cpp cannot mmap this source; the source was not copied.",
                    error
                )
            }

            val header = ByteArray(GGUF_MAGIC.size)
            val bytesRead = try {
                Os.pread(probeFd, header, 0, header.size, 0L)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Could not read the GGUF header from the SAF descriptor without changing its offset",
                    error
                )
            }
            require(bytesRead == GGUF_MAGIC.size && header.contentEquals(GGUF_MAGIC)) {
                "Invalid GGUF file on SAF descriptor: expected GGUF magic bytes at offset 0"
            }

            // Probe the same read-only mmap operation required by llama.cpp. Mapping only
            // the first page is sufficient to reject pipe/proxy descriptors and catches
            // providers/filesystems that allow lseek but deny file-backed mmap.
            val probeLength = minOf(openedStat.st_size, 4096L)
            val mapping = try {
                Os.mmap(
                    0L,
                    probeLength,
                    OsConstants.PROT_READ,
                    OsConstants.MAP_PRIVATE,
                    probeFd,
                    0L
                )
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "Selected SAF provider returned a seekable descriptor but does not support " +
                        "read-only mmap required by llama.cpp. The source was not copied.",
                    error
                )
            }
            try {
                // The mapping is a capability probe only. llama.cpp creates its own mappings.
            } finally {
                Os.munmap(mapping, probeLength)
            }

            android.util.Log.i(
                "ModelSourceResolver",
                "Validated direct SAF descriptor fd=$descriptorNumber size=${openedStat.st_size} " +
                    "seekable=true mmap=true copy=false"
            )
        } finally {
            runCatching { Os.close(probeFd) }
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
