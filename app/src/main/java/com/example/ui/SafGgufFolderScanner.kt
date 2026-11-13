package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

internal data class SafGgufDocument(
    val uri: Uri,
    val displayName: String
)

/**
 * Traverses a SAF tree without depending on filesystem paths or provider-specific
 * MIME declarations for GGUF files. Provider document IDs are opaque and are only
 * passed back to DocumentsContract.
 */
internal object SafGgufFolderScanner {
    private data class PendingDirectory(val documentId: String, val depth: Int)

    private const val MAX_DEPTH = 64
    private const val MAX_DOCUMENTS = 50_000
    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE
    )

    suspend fun scan(context: Context, treeUri: Uri): List<SafGgufDocument> =
        withContext(Dispatchers.IO) {
            require(DocumentsContract.isTreeUri(treeUri)) {
                "Android hat keine gültige Ordnerfreigabe zurückgegeben."
            }

            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val pending = ArrayDeque<PendingDirectory>()
            val visited = HashSet<String>()
            val models = ArrayList<SafGgufDocument>()
            pending.addLast(PendingDirectory(rootId, 0))

            var visitedDocuments = 0
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                if (!visited.add(directory.documentId)) continue
                require(directory.depth <= MAX_DEPTH) {
                    "Der Ordnerbaum ist tiefer als $MAX_DEPTH Ebenen; Import aus Sicherheitsgründen abgebrochen."
                }

                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    directory.documentId
                )
                val cursor = context.contentResolver.query(
                    childrenUri,
                    projection,
                    null,
                    null,
                    null
                ) ?: throw IllegalStateException("Android-Dateianbieter konnte den Ordnerinhalt nicht lesen.")

                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)

                    while (it.moveToNext()) {
                        visitedDocuments++
                        require(visitedDocuments <= MAX_DOCUMENTS) {
                            "Der Ordner enthält mehr als $MAX_DOCUMENTS Einträge; Import aus Sicherheitsgründen abgebrochen."
                        }

                        val documentId = it.getString(idColumn) ?: continue
                        val displayName = it.getString(nameColumn)?.takeIf(String::isNotBlank)
                            ?: documentId.substringAfterLast('/')
                        val mimeType = it.getString(mimeColumn)

                        if (mimeType == Document.MIME_TYPE_DIR) {
                            pending.addLast(PendingDirectory(documentId, directory.depth + 1))
                        } else if (displayName.endsWith(".gguf", ignoreCase = true)) {
                            models += SafGgufDocument(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                displayName = displayName
                            )
                        }
                    }
                }
            }

            models.distinctBy { it.uri.toString() }
                .sortedBy { it.displayName.lowercase() }
        }
}
