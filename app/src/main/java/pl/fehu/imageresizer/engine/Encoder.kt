package pl.fehu.imageresizer.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.createBitmap
import java.io.OutputStream
import pl.fehu.imageresizer.domain.model.OutputFormat

/**
 * Encodes a decoded [Bitmap] for a resolved (non-`KEEP_ORIGINAL`) [OutputFormat]. Call on
 * `Dispatchers.Default`; not main-safe.
 */
object Encoder {

    /** JPEG has no alpha channel; composite onto white first so transparency doesn't turn black. */
    fun prepareForEncode(bitmap: Bitmap, format: OutputFormat): Bitmap {
        if (format != OutputFormat.JPEG || !bitmap.hasAlpha()) return bitmap
        val composited = createBitmap(bitmap.width, bitmap.height)
        val canvas = Canvas(composited)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return composited
    }

    fun compressFormatFor(format: OutputFormat, quality: Int): Bitmap.CompressFormat = when (format) {
        OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        OutputFormat.WEBP -> webpCompressFormat(quality)
        OutputFormat.KEEP_ORIGINAL ->
            throw IllegalArgumentException("KEEP_ORIGINAL must be resolved by FormatResolver before encoding")
    }

    private fun webpCompressFormat(quality: Int): Bitmap.CompressFormat = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && quality >= 100 -> Bitmap.CompressFormat.WEBP_LOSSLESS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    /** [quality] is ignored for PNG. Returns false if the codec itself failed. */
    fun encode(bitmap: Bitmap, format: OutputFormat, quality: Int, out: OutputStream): Boolean {
        val compressFormat = compressFormatFor(format, quality)
        val effectiveQuality = if (format == OutputFormat.PNG) 100 else quality
        return bitmap.compress(compressFormat, effectiveQuality, out)
    }
}
