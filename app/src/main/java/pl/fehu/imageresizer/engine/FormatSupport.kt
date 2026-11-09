package pl.fehu.imageresizer.engine

import android.content.Context
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import pl.fehu.imageresizer.domain.model.InputFormat

/**
 * Probes which formats this device can actually decode, by decoding the bundled `assets/probe`
 * samples once. Call [probe] off the main thread; the result is safe to read from any thread.
 */
class FormatSupport private constructor(private val probedSupported: Set<InputFormat>) {

    /** JPEG/PNG/WEBP/GIF/BMP/DNG are always attempted; failures surface at decode time instead. */
    fun isSupported(format: InputFormat): Boolean = when (format) {
        InputFormat.HEIF, InputFormat.AVIF -> format in probedSupported
        InputFormat.OTHER -> false
        else -> true
    }

    companion object {
        private const val HEIC_PROBE_ASSET = "probe/sample.heic"
        private const val AVIF_PROBE_ASSET = "probe/sample.avif"

        fun probe(context: Context): FormatSupport {
            val supported = mutableSetOf<InputFormat>()
            if (canDecodeAsset(context, HEIC_PROBE_ASSET)) supported += InputFormat.HEIF
            if (canDecodeAsset(context, AVIF_PROBE_ASSET)) supported += InputFormat.AVIF
            return FormatSupport(supported)
        }

        private fun canDecodeAsset(context: Context, assetPath: String): Boolean = try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
    }
}
