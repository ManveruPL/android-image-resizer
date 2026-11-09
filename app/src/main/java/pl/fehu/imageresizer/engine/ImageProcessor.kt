package pl.fehu.imageresizer.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.scale
import java.io.OutputStream
import kotlin.math.roundToInt
import pl.fehu.imageresizer.domain.FormatResolver
import pl.fehu.imageresizer.domain.QualitySearch
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode

/** Decodes, resizes/crops and encodes one image into an already-created output document. */
interface ImageProcessor {

    /**
     * Call on `Dispatchers.Default`; not main-safe. Throws on decode/encode failure (corrupt
     * input, unsupported codec, `Bitmap.compress` failure, `OutOfMemoryError`) so the caller can
     * convert it to `ItemResult.Failed`; never swallows an exception itself.
     */
    fun process(
        inputUri: Uri,
        outputUri: Uri,
        inputFormat: InputFormat,
        config: JobConfig,
        lowRamDevice: Boolean,
    ): ProcessedImage
}

data class ProcessedImage(
    val resolvedFormat: OutputFormat,
    val outWidth: Int,
    val outHeight: Int,
    val bytesWritten: Long,
    val note: String? = null,
)

/** Default [ImageProcessor]; see application-plan.md §5.2/§5.3. */
class RealImageProcessor(private val contentResolver: ContentResolver) : ImageProcessor {

    private val decoder = Decoder(contentResolver)

    override fun process(
        inputUri: Uri,
        outputUri: Uri,
        inputFormat: InputFormat,
        config: JobConfig,
        lowRamDevice: Boolean,
    ): ProcessedImage {
        val decoded = decoder.decode(inputUri, config.resize, config.allowUpscale, config.color, lowRamDevice)
        val bitmap = decoded.bitmap
        try {
            val resolvedFormat = FormatResolver.resolve(inputFormat, config.format)
            val resizeMode = config.resize
            val outcome = if (resizeMode is ResizeMode.TargetFileSize) {
                processTargetFileSize(bitmap, resolvedFormat, resizeMode, outputUri)
            } else {
                processFixedQuality(bitmap, resolvedFormat, config.quality, outputUri)
            }
            val notes = buildList {
                if (decoded.wasAnimated) add("first frame only (animated source)")
                outcome.note?.let(::add)
            }
            return ProcessedImage(
                resolvedFormat = resolvedFormat,
                outWidth = outcome.width,
                outHeight = outcome.height,
                bytesWritten = outcome.bytes,
                note = notes.joinToString("; ").ifEmpty { null },
            )
        } finally {
            bitmap.recycle()
        }
    }

    private class EncodeOutcome(val width: Int, val height: Int, val bytes: Long, val note: String?)

    private fun processFixedQuality(
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int,
        outputUri: Uri,
    ): EncodeOutcome {
        val prepared = Encoder.prepareForEncode(bitmap, format)
        try {
            val bytes = writeToOutput(prepared, format, quality, outputUri)
            return EncodeOutcome(prepared.width, prepared.height, bytes, note = null)
        } finally {
            recycleIfDifferent(prepared, bitmap)
        }
    }

    private fun processTargetFileSize(
        bitmap: Bitmap,
        format: OutputFormat,
        mode: ResizeMode.TargetFileSize,
        outputUri: Uri,
    ): EncodeOutcome {
        check(format != OutputFormat.PNG) { "Max file size is not supported for PNG output" }
        val maxBytes = mode.maxKb * 1024
        val searchResult = QualitySearch.search(maxBytes) { quality, scale ->
            encodeToByteCount(bitmap, format, quality, scale)
        }
        val finalScaled = scaledBitmap(bitmap, searchResult.scale)
        try {
            val prepared = Encoder.prepareForEncode(finalScaled, format)
            try {
                val bytes = writeToOutput(prepared, format, searchResult.quality, outputUri)
                val note = "target size not reached".takeIf { !searchResult.targetReached }
                return EncodeOutcome(prepared.width, prepared.height, bytes, note)
            } finally {
                recycleIfDifferent(prepared, finalScaled)
            }
        } finally {
            recycleIfDifferent(finalScaled, bitmap)
        }
    }

    /** Encodes to a throwaway counting stream purely to measure size for [QualitySearch]'s search. */
    private fun encodeToByteCount(bitmap: Bitmap, format: OutputFormat, quality: Int, scale: Double): Int {
        val scaled = scaledBitmap(bitmap, scale)
        try {
            val prepared = Encoder.prepareForEncode(scaled, format)
            try {
                val counting = CountingOutputStream()
                check(Encoder.encode(prepared, format, quality, counting)) { "Bitmap.compress failed for $format" }
                return counting.count.toInt()
            } finally {
                recycleIfDifferent(prepared, scaled)
            }
        } finally {
            recycleIfDifferent(scaled, bitmap)
        }
    }

    private fun scaledBitmap(bitmap: Bitmap, scale: Double): Bitmap {
        if (scale == 1.0) return bitmap
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }

    private fun recycleIfDifferent(candidate: Bitmap, original: Bitmap) {
        if (candidate !== original) candidate.recycle()
    }

    private fun writeToOutput(bitmap: Bitmap, format: OutputFormat, quality: Int, outputUri: Uri): Long {
        val stream = contentResolver.openOutputStream(outputUri, "wt")
            ?: error("Could not open output stream for $outputUri")
        val counting = CountingOutputStream(stream)
        val ok = counting.use { Encoder.encode(bitmap, format, quality, it) }
        check(ok) { "Bitmap.compress failed for $format" }
        return counting.count
    }

    private class CountingOutputStream(private val delegate: OutputStream? = null) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate?.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate?.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate?.flush()
        }

        override fun close() {
            delegate?.close()
        }
    }
}
