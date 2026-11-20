package com.mydocanizer.llm.engine.internal

import java.io.File

/**
 * Keeps user-supplied GGUF filenames recognizable while the private model store remains
 * content-addressed. Old imports used a 64-character SHA-256 prefix; this is removed
 * only for display and never changes the persisted path used for model restoration.
 */
internal object ModelFileNamePolicy {
    private val contentHashPrefix = Regex(
        "^[0-9a-f]{64}-(?=.+\\.gguf$)",
        RegexOption.IGNORE_CASE
    )

    fun displayName(storedFileName: String): String =
        contentHashPrefix.replaceFirst(storedFileName, "")

    /**
     * Reuses an import digest only when the canonical file target is inside the
     * app-private model directory. This also supports /proc/self/fd symlinks that
     * resolve to a previously imported content-addressed GGUF.
     */
    fun trustedPrivateImportSha256(file: File, privateModelsDir: File): String? = runCatching {
        val canonicalModelsDir = privateModelsDir.canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile.parentFile != canonicalModelsDir) return@runCatching null
        val separator = canonicalFile.name.indexOf('-')
        if (separator != 64) return@runCatching null
        canonicalFile.name.substring(0, separator)
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    fun safeOriginalName(candidate: String): String {
        val leaf = candidate.replace('\\', '/')
            .substringAfterLast('/')
            .filterNot { it.isISOControl() }
            .trim()

        val hasGgufExtension = leaf.endsWith(".gguf", ignoreCase = true)
        val extension = if (hasGgufExtension) leaf.takeLast(5) else ".gguf"
        val originalStem = if (hasGgufExtension) leaf.dropLast(5) else leaf
        val safeStem = originalStem
            .filterNot { it == '/' || it == '\\' || it.isISOControl() }
            .trim()
            .trim('.')
            .take(175)
            .ifBlank { "model" }

        return "$safeStem$extension"
    }
}
