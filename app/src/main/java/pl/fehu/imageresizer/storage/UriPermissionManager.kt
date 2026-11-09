package pl.fehu.imageresizer.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Takes, releases and validates persistable URI permissions for document/tree and photo-picker
 * URIs (application-plan.md §6.2). Call on `Dispatchers.IO`; not main-safe (does `ContentResolver`
 * IPC). Share-intent URIs are not persistable at all: callers must process those immediately in
 * the foreground worker and must not pass them to [takeReadPermission].
 */
class UriPermissionManager(private val contentResolver: ContentResolver) {

    /** For input document/tree and photo-picker URIs. */
    fun takeReadPermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** For the output tree, which needs both read and write. */
    fun takeReadWritePermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /**
     * Releases a grant no other job or setting still needs; Android caps persisted grants per
     * app, so callers must always release input grants once a job ends. Best-effort cleanup:
     * never throws, even if [uri] was never persisted or was already released.
     */
    fun release(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Nothing to release; already gone.
        }
    }

    /**
     * True if [uri] still has a persisted read or write grant. Call on startup to detect a
     * revoked output folder (the user removed the folder, uninstalled and reinstalled, or hit
     * the per-app persisted-grant cap) before trying to use it.
     */
    fun isPersisted(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && (it.isReadPermission || it.isWritePermission) }
}
