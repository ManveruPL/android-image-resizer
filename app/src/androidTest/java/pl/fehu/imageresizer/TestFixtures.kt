package pl.fehu.imageresizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Generates small, self-contained test image files with real encoders (JPEG/PNG/WebP via
 * `Bitmap.compress`, EXIF via `ExifInterface`) or hand-written-but-spec-valid bytes (the minimal
 * animated GIF). No fixture here claims to be a real camera capture; see `.ai-data`/
 * `androidTest/assets` for the real HEIC/AVIF fixtures that AGENTS.md says must not be faked.
 */
object TestFixtures {

    fun jpegWithExifOrientation6AndGps(context: Context, width: Int = 400, height: Int = 300): File {
        val file = File(context.cacheDir, "fixture_exif_gps.jpg")
        writeSolidColorJpeg(file, width, height, Color.RED)
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:10 12:00:00")
        exif.setLatLong(52.2297, 21.0122)
        exif.saveAttributes()
        return file
    }

    fun pngWithAlpha(context: Context, width: Int = 200, height: Int = 200): File {
        val bitmap = createBitmap(width, height)
        Canvas(bitmap).apply {
            drawColor(Color.TRANSPARENT)
            val paint = Paint().apply { color = Color.RED }
            drawRect(width / 4f, height / 4f, 3 * width / 4f, 3 * height / 4f, paint)
        }
        val file = File(context.cacheDir, "fixture_alpha.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    fun webp(context: Context, width: Int = 200, height: Int = 150): File {
        val bitmap = solidColorBitmap(width, height, Color.BLUE)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val file = File(context.cacheDir, "fixture.webp")
        FileOutputStream(file).use { bitmap.compress(format, 80, it) }
        bitmap.recycle()
        return file
    }

    /** ~50 megapixels, to exercise the peak-memory budget (application-plan.md M2 AC). */
    fun largeJpeg(context: Context, megapixels: Double = 50.0): File {
        val width = 8192
        val height = ((megapixels * 1_000_000) / width).toInt()
        val bitmap = solidColorBitmap(width, height, Color.GREEN)
        val file = File(context.cacheDir, "fixture_50mp.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return file
    }

    fun corruptFile(context: Context): File {
        val file = File(context.cacheDir, "fixture_corrupt.jpg")
        // Valid JPEG SOI marker followed by garbage: looks like an image, decodes as none.
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5))
        return file
    }

    /** A hand-built, spec-valid 2x2, 2-frame GIF89a: frame 1 solid red, frame 2 solid green. */
    fun animatedGif(context: Context): File {
        val file = File(context.cacheDir, "fixture_animated.gif")
        file.writeBytes(minimalAnimatedGifBytes())
        return file
    }

    private fun writeSolidColorJpeg(file: File, width: Int, height: Int, color: Int) {
        val bitmap = solidColorBitmap(width, height, color)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun solidColorBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        Canvas(bitmap).drawColor(color)
        return bitmap
    }

    private fun minimalAnimatedGifBytes(): ByteArray {
        val header = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte())
        // 2x2 canvas, global color table flag set, color resolution 1, table size 4 (2^(1+1)).
        val logicalScreenDescriptor = byteArrayOf(2, 0, 2, 0, 0x91.toByte(), 0, 0)
        val globalColorTable = byteArrayOf(
            0xFF.toByte(), 0, 0, // index 0: red
            0, 0xFF.toByte(), 0, // index 1: green
            0, 0, 0xFF.toByte(), // index 2: blue
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // index 3: white
        )
        val graphicControlExtension = byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0x0A, 0, 0, 0)
        val imageDescriptor = byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0)
        // LZW (min code size 2) for Clear,0,0,0,0,EOI and Clear,1,1,1,1,EOI respectively.
        val frame1ImageData = byteArrayOf(2, 3, 0x04, 0x00, 0x05, 0)
        val frame2ImageData = byteArrayOf(2, 3, 0x4C, 0x12, 0x05, 0)
        val trailer = byteArrayOf(0x3B)
        return header + logicalScreenDescriptor + globalColorTable +
            graphicControlExtension + imageDescriptor + frame1ImageData +
            graphicControlExtension + imageDescriptor + frame2ImageData +
            trailer
    }
}
