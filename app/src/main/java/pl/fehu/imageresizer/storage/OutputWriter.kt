package pl.fehu.imageresizer.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import pl.fehu.imageresizer.domain.FileNamer
import pl.fehu.imageresizer.domain.model.ConflictPolicy

/**
 * Resolves output file names against [ConflictPolicy] and creates (or, for `OVERWRITE`, locates)
 * SAF documents in the output tree. Call on `Dispatchers.IO`; not main-safe.
 */
class OutputWriter(private val contentResolver: ContentResolver, private val outputTreeUri: Uri) : OutputResolver {

    sealed interface Resolution {
        data class Ready(val uri: Uri, val displayName: String) : Resolution
        data object Skip : Resolution
    }

    /**
     * Single cursor query over the output tree's children; call once before a batch starts and
     * reuse the result (mutated in place by [resolve]) for every item.
     */
    override fun listExistingNames(): MutableSet<String> {
        val names = mutableSetOf<String>()
        contentResolver.query(childDocumentsUri(), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    names += cursor.getString(nameIndex)
                }
            }
        return names
    }

    /**
     * Resolves [desiredName]'s conflict per [policy] against [existingNames] (a successful
     * [Resolution.Ready] reserves the chosen name in it), then creates (or for `OVERWRITE`,
     * locates) the SAF document. [Resolution.Skip] means the caller should record
     * `ItemResult.Skipped` and write nothing.
     */
    override fun resolve(
        desiredName: String,
        mimeType: String,
        policy: ConflictPolicy,
        existingNames: MutableSet<String>,
    ): Resolution {
        if (desiredName !in existingNames) return create(desiredName, mimeType, existingNames)
        return when (policy) {
            ConflictPolicy.SKIP -> Resolution.Skip
            ConflictPolicy.RENAME -> create(FileNamer.resolveConflict(desiredName, existingNames), mimeType, existingNames)
            ConflictPolicy.OVERWRITE ->
                findChildByName(desiredName)?.let { Resolution.Ready(it, desiredName) }
                    ?: create(desiredName, mimeType, existingNames)
        }
    }

    /** Deletes a partially-written document after a failed write. Never throws. */
    override fun deletePartial(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (e: Exception) {
            // Best-effort cleanup; the item has already failed for another reason.
        }
    }

    private fun create(name: String, mimeType: String, existingNames: MutableSet<String>): Resolution.Ready {
        val createdUri = DocumentsContract.createDocument(contentResolver, parentDocumentUri(), mimeType, name)
            ?: error("Could not create output document $name")
        val actualName = queryDisplayName(createdUri) ?: name
        existingNames += actualName
        return Resolution.Ready(createdUri, actualName)
    }

    private fun findChildByName(name: String): Uri? {
        contentResolver.query(
            childDocumentsUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(outputTreeUri, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

    private fun parentDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(outputTreeUri, DocumentsContract.getTreeDocumentId(outputTreeUri))

    private fun childDocumentsUri(): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(outputTreeUri, DocumentsContract.getTreeDocumentId(outputTreeUri))
}
