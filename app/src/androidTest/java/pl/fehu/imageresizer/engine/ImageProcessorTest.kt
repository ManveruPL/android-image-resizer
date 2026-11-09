package pl.fehu.imageresizer.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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

/**
 * Covers application-plan.md M2's AC: decode/encode correctness for JPEG/PNG/WebP, real HEIC
 * (OnePlus 13 capture, Display P3, GPS) and AVIF fixtures, animated-GIF first-frame handling,
 * the 50MP memory budget, and corrupt-file safety.
 */
@RunWith(AndroidJUnit4::class)
class ImageProcessorTest {

    private val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    private fun testConfig(
        resize: ResizeMode = ResizeMode.OriginalSize,
        format: OutputFormat = OutputFormat.JPEG,
        quality: Int = 90,
        allowUpscale: Boolean = false,
        color: ColorPolicy = ColorPolicy.CONVERT_TO_SRGB,
        metadata: MetadataPolicy = MetadataPolicy.KEEP_ALL,
    ): JobConfig = JobConfig(
        resize = resize,
        format = format,
        quality = quality,
        allowUpscale = allowUpscale,
        naming = NamingSpec(),
        conflict = ConflictPolicy.RENAME,
        metadata = metadata,
        color = color,
        outputTreeUri = "unused-in-engine-tests",
    )

    private fun process(inputFile: File, outputFile: File, inputFormat: InputFormat, config: JobConfig): ProcessedImage {
        val processor = RealImageProcessor(targetContext.contentResolver)
        return processor.process(Uri.fromFile(inputFile), Uri.fromFile(outputFile), inputFormat, config, lowRamDevice = false)
    }

    private fun copyTestAssetToFile(assetName: String, destName: String): File {
        val dest = File(targetContext.cacheDir, destName)
        testContext.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    @Test
    fun jpegWithOrientation6_decodesToExifOrientedDimensions() {
        val input = TestFixtures.jpegWithExifOrientation6AndGps(targetContext, width = 400, height = 300)
        val output = File(targetContext.cacheDir, "out_orientation.jpg")
        val result = process(input, output, InputFormat.JPEG, testConfig())
        // Orientation 6 (rotate 90) swaps the EXIF-oriented dimensions the decoder reports.
        assertEquals(300, result.outWidth)
        assertEquals(400, result.outHeight)
        assertEquals(OutputFormat.JPEG, result.resolvedFormat)
    }

    @Test
    fun pngWithAlpha_toJpeg_compositesOntoWhiteNotBlack() {
        val input = TestFixtures.pngWithAlpha(targetContext, width = 200, height = 200)
        val output = File(targetContext.cacheDir, "out_alpha_to_jpeg.jpg")
        process(input, output, InputFormat.PNG, testConfig(format = OutputFormat.JPEG))
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        val cornerPixel = decoded.getPixel(5, 5) // was fully transparent in the source
        decoded.recycle()
        assertEquals(Color.WHITE, cornerPixel)
    }

    @Test
    fun pngWithAlpha_toPng_keepsAlphaChannel() {
        val input = TestFixtures.pngWithAlpha(targetContext, width = 200, height = 200)
        val output = File(targetContext.cacheDir, "out_alpha_to_png.png")
        process(input, output, InputFormat.PNG, testConfig(format = OutputFormat.PNG))
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        assertTrue(decoded.hasAlpha())
        val cornerAlpha = Color.alpha(decoded.getPixel(5, 5))
        decoded.recycle()
        assertEquals(0, cornerAlpha)
    }

    @Test
    fun webp_keepOriginal_resolvesToWebpAndRoundTrips() {
        val input = TestFixtures.webp(targetContext, width = 200, height = 150)
        val output = File(targetContext.cacheDir, "out.webp")
        val result = process(input, output, InputFormat.WEBP, testConfig(format = OutputFormat.KEEP_ORIGINAL))
        assertEquals(OutputFormat.WEBP, result.resolvedFormat)
        assertEquals(200, result.outWidth)
        assertEquals(150, result.outHeight)
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        assertEquals(200, decoded.width)
        assertEquals(150, decoded.height)
        decoded.recycle()
    }

    @Test
    fun largeJpeg_50mp_stayUnderMemoryBudgetWhenDownscaled() {
        val input = TestFixtures.largeJpeg(targetContext, megapixels = 50.0)
        val output = File(targetContext.cacheDir, "out_50mp.jpg")
        System.gc()
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var peak = before
        val stop = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                try {
                    Thread.sleep(2)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        stop.start()
        val result = process(input, output, InputFormat.JPEG, testConfig(resize = ResizeMode.LongestEdge(2000)))
        stop.interrupt()
        stop.join()
        val deltaMb = (peak - before) / (1024.0 * 1024.0)
        assertTrue("peak extra heap usage was ${deltaMb}MB (budget 150MB)", deltaMb < 150.0)
        assertEquals(2000, maxOf(result.outWidth, result.outHeight))
    }

    @Test
    fun realHeic_originalSize_matchesExifOrientedSourceDimensions() {
        val input = copyTestAssetToFile("heic_portrait_gps_p3.heic", "input_portrait.heic")
        val output = File(targetContext.cacheDir, "out_heic_portrait.jpg")
        val result = process(
            input,
            output,
            InputFormat.HEIF,
            testConfig(resize = ResizeMode.OriginalSize, format = OutputFormat.JPEG, color = ColorPolicy.CONVERT_TO_SRGB),
        )
        assertEquals(OutputFormat.JPEG, result.resolvedFormat)
        assertEquals(3696, result.outWidth)
        assertEquals(6560, result.outHeight)
    }

    @Test
    fun realHeic_landscapeVariant_decodesCorrectly() {
        val input = copyTestAssetToFile("heic_landscape_gps_p3.heic", "input_landscape.heic")
        val output = File(targetContext.cacheDir, "out_heic_landscape.jpg")
        val result = process(
            input,
            output,
            InputFormat.HEIF,
            testConfig(resize = ResizeMode.OriginalSize, format = OutputFormat.JPEG),
        )
        assertEquals(6560, result.outWidth)
        assertEquals(3696, result.outHeight)
    }

    @Test
    fun realAvif_decodesAndConvertsToJpeg() {
        // AVIF decode is only guaranteed from API 31+ (application-plan.md §5.5).
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val input = copyTestAssetToFile("sample.avif", "input.avif")
        val output = File(targetContext.cacheDir, "out_avif.jpg")
        val result = process(input, output, InputFormat.AVIF, testConfig(format = OutputFormat.JPEG))
        assertEquals(OutputFormat.JPEG, result.resolvedFormat)
        assertEquals(1396, result.outWidth)
        assertEquals(694, result.outHeight)
    }

    @Test
    fun animatedGif_decodesFirstFrameOnly_andNotesIt() {
        val input = TestFixtures.animatedGif(targetContext)
        val output = File(targetContext.cacheDir, "out_gif.jpg")
        val result = process(input, output, InputFormat.GIF, testConfig(format = OutputFormat.JPEG, quality = 95))
        assertEquals(2, result.outWidth)
        assertEquals(2, result.outHeight)
        assertNotNull(result.note)
        assertTrue(result.note!!.contains("first frame"))
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        val pixel = decoded.getPixel(0, 0)
        decoded.recycle()
        // First frame is solid red; allow slack for JPEG quantization.
        assertTrue(Color.red(pixel) > 200)
        assertTrue(Color.green(pixel) < 60)
        assertTrue(Color.blue(pixel) < 60)
    }

    @Test
    fun corruptFile_throwsCatchableException_doesNotCrash() {
        val input = TestFixtures.corruptFile(targetContext)
        val output = File(targetContext.cacheDir, "out_corrupt.jpg")
        try {
            process(input, output, InputFormat.JPEG, testConfig())
            fail("expected decoding a corrupt file to throw")
        } catch (e: Exception) {
            // Expected: a normal, catchable exception -- exactly what lets a caller produce
            // ItemResult.Failed instead of crashing the batch (hard rule #6).
        }
    }
}
