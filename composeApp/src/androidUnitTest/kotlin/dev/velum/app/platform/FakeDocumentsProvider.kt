package dev.velum.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A folder holding two audio files, as the Storage Access Framework shows it. Synthetic names only. */
class FakeDocumentsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns)
        if (uri.lastPathSegment == "children") {
            CHILDREN.forEach { child -> cursor.addRow(columns.map { value(it, child) }) }
        } else {
            cursor.addRow(columns.map { value(it, uri.lastPathSegment.orEmpty()) })
        }
        return cursor
    }

    private fun value(column: String, id: String): Any? = when (column) {
        Document.COLUMN_DOCUMENT_ID -> id
        Document.COLUMN_DISPLAY_NAME -> "$id.wav"
        Document.COLUMN_MIME_TYPE -> if (id == ROOT) Document.MIME_TYPE_DIR else "audio/wav"
        Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
        Document.COLUMN_SIZE -> 64L
        Document.COLUMN_FLAGS -> 0
        else -> null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "dev.velum.test.documents"
        private const val ROOT = "synthetic-root"
        private val CHILDREN = listOf("synthetic-one", "synthetic-two")

        fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)
    }
}
