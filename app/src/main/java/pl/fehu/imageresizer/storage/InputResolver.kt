package pl.fehu.imageresizer.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import pl.fehu.imageresizer.domain.FormatResolver
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.InputImage

/**
 * Resolves picked URIs (photo picker, files picker, share intent) and SAF folder trees into
 * [InputImage] lists. Call on `Dispatchers.IO`; not main-safe (does `ContentResolver` I/O).
 */
class InputResolver(private val contentResolver: ContentResolver) {

    /**
     * For the photo picker, files picker and share-intent routes: resolves each URI's display
     * name, MIME type and size, deduplicating by URI. URIs that can't be queried are dropped.
     */
    fun resolveUris(uris: List<Uri>): List<InputImage> {
        val seen = LinkedHashSet<Uri>()
        val result = mutableListOf<InputImage>()
        for (uri in uris) {
            if (!seen.add(uri)) continue
            resolveSingle(uri)?.let { result += it }
        }
        return result
    }

    /**
     * Enumerates a folder tree's recognized-image children with a single cursor query per
     * folder level (per-item `DocumentFile.listFiles()` is too slow for large folders). Any
     * `image` MIME type is included, as are other MIME types (e.g. `application/octet-stream`)
     * whose extension is a known image format (HEIC often arrives this way). [recursive] also
     * descends into subfolders; off by default.
     */
    fun resolveFolder(treeUri: Uri, recursive: Boolean = false): List<InputImage> {
        val result = mutableListOf<InputImage>()
        enumerateChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri), recursive, result)
        return result
    }

    private fun resolveSingle(uri: Uri): InputImage? {
        var displayName: String? = null
        var size: Long? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        val name = displayName ?: uri.lastPathSegment ?: return null
        val mimeType = contentResolver.getType(uri)
        val format = FormatResolver.detect(mimeType, name)
        return InputImage(uri.toString(), name, mimeType ?: "application/octet-stream", format, size)
    }

    private fun enumerateChildren(treeUri: Uri, parentDocumentId: String, recursive: Boolean, out: MutableList<InputImage>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val subfolderDocIds = mutableListOf<String>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val mimeType = cursor.getString(mimeIndex)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (recursive) subfolderDocIds += documentId
                    continue
                }
                val name = cursor.getString(nameIndex)
                val format = FormatResolver.detect(mimeType, name)
                if (format == InputFormat.OTHER) continue
                val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                out += InputImage(documentUri.toString(), name, mimeType ?: "application/octet-stream", format, size)
            }
        }
        for (subfolderDocId in subfolderDocIds) {
            enumerateChildren(treeUri, subfolderDocId, recursive, out)
        }
    }
}
