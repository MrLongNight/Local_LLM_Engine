package com.mydocanizer.llm.engine

import java.io.File
import java.io.FileDescriptor

/**
 * Represents the origin and location of an LLM model file (.gguf).
 */
sealed interface ModelSource {
    /**
     * Absolute filesystem path to the model file on device storage.
     */
    data class FilePath(
        val path: String,
        /**
         * Borrowed descriptor for a direct /proc/self/fd/N source. The caller owns it
         * and must keep it open until loadModel returns. It is never closed by the engine.
         */
        val descriptor: FileDescriptor? = null,
        /** Numeric value of [descriptor], required by JNI; kept explicit to avoid hidden JDK fields. */
        val descriptorFd: Int? = null
    ) : ModelSource {
        init {
            require(path.isNotBlank()) { "Path must not be blank" }
            require((descriptor == null) == (descriptorFd == null)) {
                "Direct model descriptor and descriptor number must be supplied together"
            }
            require(descriptorFd == null || descriptorFd >= 0) {
                "Direct model descriptor number must be non-negative"
            }
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
     * Remote HTTPS URL for downloading a GGUF model into app-private model storage.
     */
    data class RemoteUrl(
        val url: String,
        val suggestedFileName: String? = null
    ) : ModelSource
}
