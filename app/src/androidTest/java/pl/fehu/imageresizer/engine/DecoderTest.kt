package pl.fehu.imageresizer.engine

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.TestFixtures

/**
 * [Decoder.readDimensions] aborts the decode from inside `ImageDecoder`'s header-decoded
 * listener, so it needs its own real-device check: application-plan.md's naming (`{w}x{h}`
 * tokens, §5.3) depends on it reporting EXIF-oriented dimensions without decoding any pixels.
 */
@RunWith(AndroidJUnit4::class)
class DecoderTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val decoder get() = Decoder(context.contentResolver)

    @Test
    fun readDimensions_plainJpeg_matchesWidthAndHeight() {
        val file = TestFixtures.jpegWithExifOrientation6AndGps(context, width = 400, height = 300)
        // Orientation 6 (rotate 90) swaps the EXIF-oriented dimensions, same as the full decode.
        val (width, height) = decoder.readDimensions(Uri.fromFile(file))
        assertEquals(300, width)
        assertEquals(400, height)
    }

    @Test
    fun readDimensions_png_matchesSourceDimensions() {
        val file = TestFixtures.pngWithAlpha(context, width = 200, height = 150)
        val (width, height) = decoder.readDimensions(Uri.fromFile(file))
        assertEquals(200, width)
        assertEquals(150, height)
    }

    @Test(expected = Exception::class)
    fun readDimensions_corruptFile_throws() {
        val file = TestFixtures.corruptFile(context)
        decoder.readDimensions(Uri.fromFile(file))
    }
}
