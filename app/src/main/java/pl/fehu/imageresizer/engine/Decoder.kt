package pl.fehu.imageresizer.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import pl.fehu.imageresizer.domain.DimensionCalculator
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.domain.model.TargetDims
import android.graphics.Rect as AndroidRect

/**
 * Decodes an image via [ImageDecoder] with EXIF orientation already applied, resizing (and,
 * for `FILL`, center-cropping) in the same decode pass. HEIF containers are decoded to their
 * primary image automatically by the platform. Call on `Dispatchers.Default`; not main-safe
 * (touches the `ContentResolver` and does CPU-heavy decode work).
 */
class Decoder(private val contentResolver: ContentResolver) {

    class Decoded(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetDims: TargetDims,
        /** True if the source was an animated GIF/WebP; only its first frame was decoded. */
        val wasAnimated: Boolean,
    )

    fun decode(
        uri: Uri,
        resize: ResizeMode,
        allowUpscale: Boolean,
        color: ColorPolicy,
        lowRamDevice: Boolean,
    ): Decoded {
        val source = ImageDecoder.createSource(contentResolver, uri)
        var sourceWidth = 0
        var sourceHeight = 0
        var dims: TargetDims? = null
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            val computed = DimensionCalculator.compute(sourceWidth, sourceHeight, resize, allowUpscale)
            dims = computed
            decoder.setTargetSize(computed.decodeWidth, computed.decodeHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            computed.cropRect?.let { r -> decoder.crop = AndroidRect(r.left, r.top, r.right, r.bottom) }
            if (lowRamDevice) {
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
            if (color == ColorPolicy.CONVERT_TO_SRGB) {
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }
        val wasAnimated = drawable is AnimatedImageDrawable
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else firstFrameOf(drawable)
        return Decoded(bitmap, sourceWidth, sourceHeight, requireNotNull(dims), wasAnimated)
    }

    /**
     * EXIF-oriented source width/height without decoding any pixels (aborts the decode from
     * inside the header-decoded listener). Used to build output file names ("{w}x{h}") before
     * the output document exists, i.e. before the real decode+encode pass. Call on
     * `Dispatchers.Default` or `Dispatchers.IO`; not main-safe.
     */
    fun readDimensions(uri: Uri): Pair<Int, Int> {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return try {
            ImageDecoder.decodeDrawable(source) { _, info, _ ->
                throw HeaderDimensions(info.size.width, info.size.height)
            }
            error("unreachable: the header-decoded listener always throws")
        } catch (e: HeaderDimensions) {
            e.width to e.height
        }
    }

    private class HeaderDimensions(val width: Int, val height: Int) : RuntimeException()

    /** Renders an [AnimatedImageDrawable]'s (unstarted, so frame 0) current frame into a bitmap. */
    private fun firstFrameOf(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
