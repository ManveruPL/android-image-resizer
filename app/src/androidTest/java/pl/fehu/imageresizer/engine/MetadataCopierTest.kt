package pl.fehu.imageresizer.engine

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.TestFixtures
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode

/** Covers §5.4's KEEP_ALL/STRIP_GPS/STRIP_ALL behavior and the always-normalized tags. */
@RunWith(AndroidJUnit4::class)
class MetadataCopierTest {

    private val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Produces a JPEG output (via the real engine pipeline) from the synthetic EXIF+GPS fixture. */
    private fun processToJpeg(outputName: String): Pair<File, File> {
        val input = TestFixtures.jpegWithExifOrientation6AndGps(targetContext, width = 400, height = 300)
        val output = File(targetContext.cacheDir, outputName)
        val config = JobConfig(
            resize = ResizeMode.OriginalSize,
            format = OutputFormat.JPEG,
            quality = 90,
            naming = NamingSpec(),
            conflict = ConflictPolicy.RENAME,
            metadata = MetadataPolicy.KEEP_ALL,
            color = ColorPolicy.CONVERT_TO_SRGB,
            outputTreeUri = "unused",
        )
        RealImageProcessor(targetContext.contentResolver)
            .process(Uri.fromFile(input), Uri.fromFile(output), InputFormat.JPEG, config, lowRamDevice = false)
        return input to output
    }

    @Test
    fun keepAll_copiesDateAndGps_andNormalizesOrientationAndDimensions() {
        val (input, output) = processToJpeg("meta_keep_all.jpg")
        val note = MetadataCopier.copy(
            ExifInterface(input.absolutePath),
            ExifInterface(output.absolutePath),
            MetadataPolicy.KEEP_ALL,
            finalWidth = 300,
            finalHeight = 400,
        )
        assertNull(note)
        val reread = ExifInterface(output.absolutePath)
        assertEquals("2026:10:10 12:00:00", reread.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull(reread.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(ExifInterface.ORIENTATION_NORMAL.toString(), reread.getAttribute(ExifInterface.TAG_ORIENTATION))
        assertEquals("300", reread.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertEquals("400", reread.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION))
    }

    @Test
    fun stripGps_keepsDateButRemovesGps() {
        val (input, output) = processToJpeg("meta_strip_gps.jpg")
        val note = MetadataCopier.copy(
            ExifInterface(input.absolutePath),
            ExifInterface(output.absolutePath),
            MetadataPolicy.STRIP_GPS,
            finalWidth = 300,
            finalHeight = 400,
        )
        assertNull(note)
        val reread = ExifInterface(output.absolutePath)
        assertEquals("2026:10:10 12:00:00", reread.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(reread.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test
    fun stripAll_copiesNothingButStillNormalizesOrientationAndDimensions() {
        val (input, output) = processToJpeg("meta_strip_all.jpg")
        val note = MetadataCopier.copy(
            ExifInterface(input.absolutePath),
            ExifInterface(output.absolutePath),
            MetadataPolicy.STRIP_ALL,
            finalWidth = 300,
            finalHeight = 400,
        )
        assertNull(note)
        val reread = ExifInterface(output.absolutePath)
        assertNull(reread.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(reread.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(ExifInterface.ORIENTATION_NORMAL.toString(), reread.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun nullSourceExif_stillNormalizesOutputTags() {
        val (_, output) = processToJpeg("meta_null_source.jpg")
        val note = MetadataCopier.copy(
            sourceExif = null,
            outputExif = ExifInterface(output.absolutePath),
            policy = MetadataPolicy.KEEP_ALL,
            finalWidth = 300,
            finalHeight = 400,
        )
        assertNull(note)
        val reread = ExifInterface(output.absolutePath)
        assertEquals(ExifInterface.ORIENTATION_NORMAL.toString(), reread.getAttribute(ExifInterface.TAG_ORIENTATION))
    }
}
