package com.mydocanizer.llm.engine.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal object ModelSourceResolver {

    suspend fun resolveToFile(context: Context, source: ModelSource): File = withContext(Dispatchers.IO) {
        when (source) {
            is ModelSource.FilePath -> {
                val f = source.file
                require(f.exists()) { "Model file does not exist at path: ${source.path}" }
                require(f.canRead()) { "Cannot read model file at path: ${source.path}" }
                f
            }

            is ModelSource.ContentUri -> {
                val uri = Uri.parse(source.uriString)
                val targetDir = File(context.filesDir, "models").apply { mkdirs() }
                val displayName = source.displayName ?: queryFileName(context, uri) ?: "imported_model.gguf"
                val destFile = File(targetDir, displayName)

                // If already copied and size matches, reuse it
                val cr = context.contentResolver
                cr.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("Could not open Content URI: ${source.uriString}")

                destFile
            }

            is ModelSource.AssetPath -> {
                val targetDir = File(context.filesDir, "models").apply { mkdirs() }
                val destFile = File(targetDir, source.assetName)
                if (!destFile.exists()) {
                    context.assets.open(source.assetName).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                destFile
            }

            is ModelSource.RemoteUrl -> {
                val targetDir = File(context.filesDir, "models").apply { mkdirs() }
                val fileName = source.suggestedFileName ?: "downloaded_model.gguf"
                File(targetDir, fileName)
            }
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }
}
