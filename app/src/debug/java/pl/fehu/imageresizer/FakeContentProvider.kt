package pl.fehu.imageresizer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException

/**
 * A minimal, plain `ContentProvider` (not `DocumentsProvider` -- that requires the
 * signature-level `MANAGE_DOCUMENTS` permission, which a regular app can never hold) backed by
 * files in the app's cache dir. Gives instrumented tests a real, queryable `content://` URI for
 * `InputResolver`, and a real resolvable authority so `grantUriPermission`/
 * `takePersistableUriPermission` have something to register against, without needing MediaStore
 * (which needs `WRITE_EXTERNAL_STORAGE` on API 28, a permission this app's hard rules forbid
 * adding) or a real SAF tree.
 *
 * Lives in the `debug` source set (declared in `src/debug/AndroidManifest.xml`), not
 * `androidTest`: a provider the OS instantiates as its own component runs under the declaring
 * *package's* UID with only that package's own dex and private storage. Declaring it in
 * `androidTest` puts it in the separate `pl.fehu.imageresizer.test` package, which (confirmed
 * empirically -- see DECISIONS.md) breaks in three independent ways: AGP excludes `kotlin-stdlib`
 * from the test APK (so a Kotlin-written provider crashes with `NoClassDefFoundError` once the
 * OS spawns it standalone), and even fixing that, the spawned process's static state and cache
 * dir are isolated from the test code that populated them (different UID). Living in `debug`
 * keeps it part of the app's own package (same UID, same dex, shared state) while never
 * reaching a release build.
 *
 * Also answers `DocumentsContract`-shaped "children" queries (see [treeUriFor]) against a real
 * directory, for `InputResolver.resolveFolder` tests -- without extending `DocumentsProvider`
 * itself, which requires the signature-level `MANAGE_DOCUMENTS` permission regardless of which
 * package declares it (confirmed empirically). `createDocument`/`openDocument` for a tree aren't
 * implemented since nothing here needs them; `OutputWriter`'s real SAF calls are untested for a
 * different, unrelated reason (see DECISIONS.md).
 */
class FakeContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = registry[uri]?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val segments = uri.pathSegments
        if (segments.size >= 2 && segments[0] == "tree" && segments.last() == "children") {
            return queryChildren(File(segments[segments.size - 2]), projection)
        }
        val entry = registry[uri] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(entry.displayName)
                OpenableColumns.SIZE -> row.add(entry.file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    private fun queryChildren(parent: File, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = MatrixCursor(columns)
        for (child in parent.listFiles().orEmpty().sortedBy { it.name }) {
            val row = cursor.newRow()
            for (column in columns) {
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(child.absolutePath)
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(child.name)
                    DocumentsContract.Document.COLUMN_MIME_TYPE ->
                        row.add(if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeTypeFor(child.name))
                    DocumentsContract.Document.COLUMN_SIZE -> row.add(child.length())
                    else -> row.add(null)
                }
            }
        }
        return cursor
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "application/octet-stream" // matches real-world providers that mislabel HEIC
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = registry[uri] ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "pl.fehu.imageresizer.test.fakecontent"

        private class Entry(val displayName: String, val mimeType: String, val file: File)

        private val registry = mutableMapOf<Uri, Entry>()
        private var nextId = 0

        /** Writes [bytes] to a cache file and returns a real queryable `content://` URI for it. */
        fun register(context: Context, displayName: String, mimeType: String, bytes: ByteArray): Uri {
            val id = nextId++
            val file = File(context.cacheDir, "fake_provider_${id}_$displayName")
            file.writeBytes(bytes)
            val uri = "content://$AUTHORITY/$id".toUri()
            registry[uri] = Entry(displayName, mimeType, file)
            return uri
        }

        /** A real, queryable tree `Uri` rooted at [rootDir], for `DocumentsContract`-shaped children queries. */
        fun treeUriFor(rootDir: File): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootDir.absolutePath)
    }
}
