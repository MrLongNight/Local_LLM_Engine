package com.mydocanizer.llm.engine.internal

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
