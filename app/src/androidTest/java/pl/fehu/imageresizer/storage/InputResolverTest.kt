package pl.fehu.imageresizer.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.FakeContentProvider
import pl.fehu.imageresizer.domain.model.InputFormat

/**
 * Covers application-plan.md M3's AC: each input route lists correct images; folder enumeration
 * scales. Uses `FakeContentProvider` (see its KDoc, and DECISIONS.md) for real, queryable
 * `content://` and `DocumentsContract`-shaped tree URIs, since MediaStore insert needs
 * `WRITE_EXTERNAL_STORAGE` on API 28 (forbidden by this app's hard rules) and a real SAF tree
 * needs interactive picker consent this test harness can't automate.
 */
@RunWith(AndroidJUnit4::class)
class InputResolverTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = InputResolver(context.contentResolver)

    @Test
    fun resolveUris_readsDisplayNameMimeAndSize_fromRealContentUris() {
        val uri = FakeContentProvider.register(context, "resolver_test.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5))
        val result = resolver.resolveUris(listOf(uri))
        assertEquals(1, result.size)
        val image = result.single()
        assertEquals("resolver_test.jpg", image.displayName)
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(InputFormat.JPEG, image.format)
        assertEquals(5L, image.sizeBytes)
    }

    @Test
    fun resolveUris_detectsHeicFromExtension_whenMimeIsGenericOctetStream() {
        val uri = FakeContentProvider.register(context, "IMG_0001.heic", "application/octet-stream", byteArrayOf(1))
        val result = resolver.resolveUris(listOf(uri))
        assertEquals(InputFormat.HEIF, result.single().format)
    }

    @Test
    fun resolveUris_deduplicatesByUri() {
        val uri = FakeContentProvider.register(context, "resolver_dedup.jpg", "image/jpeg", byteArrayOf(1))
        val result = resolver.resolveUris(listOf(uri, uri, uri))
        assertEquals(1, result.size)
    }

    @Test
    fun resolveUris_multipleDistinctUris_allResolved() {
        val uri1 = FakeContentProvider.register(context, "resolver_multi1.jpg", "image/jpeg", byteArrayOf(1))
        val uri2 = FakeContentProvider.register(context, "resolver_multi2.png", "image/png", byteArrayOf(1))
        val result = resolver.resolveUris(listOf(uri1, uri2))
        assertEquals(2, result.size)
        assertEquals(setOf(InputFormat.JPEG, InputFormat.PNG), result.map { it.format }.toSet())
    }

    private fun newTestTree(): File {
        val dir = File(context.cacheDir, "input_tree_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun resolveFolder_listsOnlyRecognizedImageFormats() {
        val dir = newTestTree()
        File(dir, "photo.jpg").writeBytes(byteArrayOf(1))
        File(dir, "photo.png").writeBytes(byteArrayOf(1))
        File(dir, "note.txt").writeBytes(byteArrayOf(1))
        // Octet-stream MIME but a recognized HEIC extension must still be included (§6.1).
        File(dir, "IMG_0001.heic").writeBytes(byteArrayOf(1))

        val result = resolver.resolveFolder(FakeContentProvider.treeUriFor(dir))
        val names = result.map { it.displayName }.toSet()
        assertEquals(setOf("photo.jpg", "photo.png", "IMG_0001.heic"), names)
        assertEquals(InputFormat.HEIF, result.first { it.displayName == "IMG_0001.heic" }.format)
    }

    @Test
    fun resolveFolder_nonRecursiveByDefault_ignoresSubfolders() {
        val dir = newTestTree()
        File(dir, "top.jpg").writeBytes(byteArrayOf(1))
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "nested.jpg").writeBytes(byteArrayOf(1))

        val result = resolver.resolveFolder(FakeContentProvider.treeUriFor(dir))
        assertEquals(listOf("top.jpg"), result.map { it.displayName })
    }

    @Test
    fun resolveFolder_recursiveTrue_descendsIntoSubfolders() {
        val dir = newTestTree()
        File(dir, "top.jpg").writeBytes(byteArrayOf(1))
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "nested.jpg").writeBytes(byteArrayOf(1))

        val result = resolver.resolveFolder(FakeContentProvider.treeUriFor(dir), recursive = true)
        assertEquals(setOf("top.jpg", "nested.jpg"), result.map { it.displayName }.toSet())
    }

    @Test
    fun resolveFolder_oneThousandFiles_enumeratesInUnderTwoSeconds() {
        val dir = newTestTree()
        repeat(1000) { i -> File(dir, "img_%04d.jpg".format(i)).writeBytes(byteArrayOf(1)) }

        val treeUri = FakeContentProvider.treeUriFor(dir)
        val start = System.currentTimeMillis()
        val result = resolver.resolveFolder(treeUri)
        val elapsedMs = System.currentTimeMillis() - start

        assertEquals(1000, result.size)
        assertTrue("folder enumeration took ${elapsedMs}ms (budget 2000ms)", elapsedMs < 2000)
    }
}
