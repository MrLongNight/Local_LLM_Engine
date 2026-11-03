package com.mydocanizer.llm.engine

import java.io.File

/**
 * Represents the origin and location of an LLM model file (.gguf).
 */
sealed interface ModelSource {
    /**
     * Absolute filesystem path to the model file on device storage.
     */
    data class FilePath(val path: String) : ModelSource {
        init {
            require(path.isNotBlank()) { "Path must not be blank" }
        }
        val file: File get() = File(path)
    }

    /**
     * Content URI from Android's Storage Access Framework or FilePicker.
     */
    data class ContentUri(
        val uriString: String,
        val displayName: String? = null
    ) : ModelSource

    /**
     * Asset packaged in the APK/AAB assets directory.
     */
    data class AssetPath(val assetName: String) : ModelSource

    /**
     * Remote URL for downloading a GGUF model into cache.
     */
    data class RemoteUrl(
        val url: String,
        val suggestedFileName: String? = null
    ) : ModelSource
}
