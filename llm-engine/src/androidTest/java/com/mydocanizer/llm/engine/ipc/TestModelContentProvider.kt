package com.mydocanizer.llm.engine.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Instrumentation-only seekable file provider. It exposes a staged test GGUF from the
 * test APK's private cache so the client can exercise ContentUri -> Binder PFD transfer.
 */
class TestModelContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Test model provider supports read-only access only" }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only test provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only test provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Read-only test provider")

    private fun resolveFile(uri: Uri): File {
        require(uri.authority == AUTHORITY) { "Unexpected test provider authority" }
        val name = uri.lastPathSegment ?: throw IllegalArgumentException("Missing model file name")
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}"))) { "Invalid test model file name" }
        val root = requireNotNull(context).cacheDir.canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root && file.isFile && file.length() > 0L) {
            "Test model file is missing or outside the provider cache"
        }
        return file
    }

    companion object {
        const val AUTHORITY = "com.mydocanizer.llm.engine.test-model-provider"
    }
}
