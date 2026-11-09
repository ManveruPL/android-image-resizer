package pl.fehu.imageresizer.domain

import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.OutputFormat

/**
 * Resolves `KEEP_ORIGINAL` to a concrete output format, and detects [InputFormat] from MIME type
 * and file extension. See application-plan.md §5.5 and §6.1.
 */
object FormatResolver {

    /** Explicit formats pass through; `KEEP_ORIGINAL` maps formats nothing can re-encode to JPEG. */
    fun resolve(input: InputFormat, requested: OutputFormat): OutputFormat = when (requested) {
        OutputFormat.JPEG, OutputFormat.PNG, OutputFormat.WEBP -> requested
        OutputFormat.KEEP_ORIGINAL -> when (input) {
            InputFormat.JPEG -> OutputFormat.JPEG
            InputFormat.PNG -> OutputFormat.PNG
            InputFormat.WEBP -> OutputFormat.WEBP
            InputFormat.HEIF, InputFormat.AVIF, InputFormat.GIF, InputFormat.BMP, InputFormat.DNG, InputFormat.OTHER ->
                OutputFormat.JPEG
        }
    }

    fun mimeTypeFor(format: OutputFormat): String = when (format) {
        OutputFormat.JPEG -> "image/jpeg"
        OutputFormat.PNG -> "image/png"
        OutputFormat.WEBP -> "image/webp"
        OutputFormat.KEEP_ORIGINAL ->
            throw IllegalArgumentException("KEEP_ORIGINAL must be resolved before requesting a MIME type")
    }

    /**
     * MIME first; falls back to the file extension (case-insensitive) when the MIME type is
     * missing or not one of the known image types, because some providers report HEIC files as
     * `application/octet-stream`.
     */
    fun detect(mimeType: String?, fileName: String): InputFormat {
        mimeType?.let(::fromMime)?.let { return it }
        fromExtension(fileName)?.let { return it }
        return InputFormat.OTHER
    }

    private fun fromMime(mimeType: String): InputFormat? = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> InputFormat.JPEG
        "image/png" -> InputFormat.PNG
        "image/webp" -> InputFormat.WEBP
        "image/heic", "image/heif" -> InputFormat.HEIF
        "image/avif" -> InputFormat.AVIF
        "image/gif" -> InputFormat.GIF
        "image/bmp", "image/x-ms-bmp" -> InputFormat.BMP
        "image/x-adobe-dng" -> InputFormat.DNG
        else -> null
    }

    private fun fromExtension(fileName: String): InputFormat? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> InputFormat.JPEG
            "png" -> InputFormat.PNG
            "webp" -> InputFormat.WEBP
            "heic", "heif" -> InputFormat.HEIF
            "avif" -> InputFormat.AVIF
            "gif" -> InputFormat.GIF
            "bmp" -> InputFormat.BMP
            "dng" -> InputFormat.DNG
            else -> null
        }
    }
}
