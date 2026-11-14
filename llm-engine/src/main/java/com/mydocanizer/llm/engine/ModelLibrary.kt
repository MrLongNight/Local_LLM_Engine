package com.mydocanizer.llm.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Imports GGUF files into the app-private model library without loading them into RAM.
 * The original SAF documents are never modified.
 */
object ModelLibrary {
    /**
     * Converts an internal content-addressed filename to the original model filename
     * for user-facing lists. Legacy imports are kept on disk so remembered paths remain valid.
     */
    fun displayName(storedFileName: String): String =
        com.mydocanizer.llm.engine.internal.ModelFileNamePolicy.displayName(storedFileName)

    /**
     * Streams one SAF document into the verified private model store.
     * Existing content-addressed imports are reused when provider metadata permits.
     */
    suspend fun importFromSaf(context: Context, uri: Uri, displayName: String? = null): File {
        require(uri.scheme == "content") { "Only Android content URIs can be imported" }
        return com.mydocanizer.llm.engine.internal.ModelSourceResolver.resolveToFile(
            context.applicationContext,
            ModelSource.ContentUri(uriString = uri.toString(), displayName = displayName)
        )
    }
}
