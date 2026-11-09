package pl.fehu.imageresizer.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.FakeContentProvider

/**
 * Covers application-plan.md M3's AC: a revoked output folder is detected. `takePersistableUriPermission`
 * / `releasePersistableUriPermission` / `persistedUriPermissions` operate on the system's grant
 * table keyed by URI string, but registering a grant does need the authority to resolve to a
 * real provider -- hence reusing `FakeContentProvider`'s authority (it doesn't need to actually
 * serve these specific tree-shaped URIs for this).
 */
@RunWith(AndroidJUnit4::class)
class UriPermissionManagerTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = UriPermissionManager(context.contentResolver)

    private fun fakeTreeUri(id: String): Uri = DocumentsContract.buildTreeDocumentUri(FakeContentProvider.AUTHORITY, id)

    @Test
    fun isPersisted_falseForAUriThatWasNeverGranted() {
        assertFalse(manager.isPersisted(fakeTreeUri("never_granted_${System.nanoTime()}")))
    }

    @Test
    fun takeAndReleaseReadWritePermission_roundTripsThroughPersistedUriPermissions() {
        val treeUri = fakeTreeUri("grant_roundtrip_${System.nanoTime()}")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        // Self-grant to establish the temporary Uri permission a real picker result would have
        // carried as Intent extras; takePersistableUriPermission only promotes an existing grant.
        context.grantUriPermission(context.packageName, treeUri, flags)

        manager.takeReadWritePermission(treeUri)
        assertTrue(manager.isPersisted(treeUri))

        manager.release(treeUri)
        assertFalse(manager.isPersisted(treeUri))
    }

    @Test
    fun release_isNoOpAndNeverThrows_forAUriThatWasNeverGranted() {
        val treeUri = fakeTreeUri("release_noop_${System.nanoTime()}")
        manager.release(treeUri) // must not throw
        assertFalse(manager.isPersisted(treeUri))
    }

    @Test
    fun isPersisted_distinguishesDifferentUris() {
        val treeA = fakeTreeUri("grant_a_${System.nanoTime()}")
        val treeB = fakeTreeUri("grant_b_${System.nanoTime()}")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        context.grantUriPermission(context.packageName, treeA, flags)
        manager.takeReadPermission(treeA)

        assertTrue(manager.isPersisted(treeA))
        assertFalse(manager.isPersisted(treeB))
        manager.release(treeA)
    }
}
