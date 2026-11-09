package pl.fehu.imageresizer.domain

import pl.fehu.imageresizer.domain.model.OutputFormat

/**
 * Builds and sanitizes output file names from a [pl.fehu.imageresizer.domain.model.NamingSpec]
 * pattern, and resolves name conflicts against an existing-names set.
 */
object FileNamer {

    private val ILLEGAL_CHARS = Regex("[/\\\\:*?\"<>|]")
    private const val MAX_BASE_NAME_LENGTH = 120

    /**
     * Substitutes naming tokens, sanitizes the result and falls back to `image_{index}` when the
     * sanitized name is empty. [index] is 1-based; it is zero-padded to the digit count of
     * [batchSize].
     */
    fun buildBaseName(
        pattern: String,
        originalName: String,
        width: Int,
        height: Int,
        index: Int,
        batchSize: Int,
        date: String,
        presetName: String?,
    ): String {
        val nameWithoutExt = originalName.substringBeforeLast('.', originalName)
        val digits = maxOf(1, batchSize.toString().length)
        val indexStr = index.toString().padStart(digits, '0')
        val raw = pattern
            .replace("{name}", nameWithoutExt)
            .replace("{w}", width.toString())
            .replace("{h}", height.toString())
            .replace("{index}", indexStr)
            .replace("{date}", date)
            .replace("{preset}", presetName.orEmpty())
        val sanitized = sanitize(raw)
        return sanitized.ifEmpty { "image_$indexStr" }
    }

    fun extensionFor(format: OutputFormat): String = when (format) {
        OutputFormat.JPEG -> "jpg"
        OutputFormat.PNG -> "png"
        OutputFormat.WEBP -> "webp"
        OutputFormat.KEEP_ORIGINAL ->
            throw IllegalArgumentException("KEEP_ORIGINAL must be resolved by FormatResolver before naming")
    }

    fun withExtension(baseName: String, format: OutputFormat): String = "$baseName.${extensionFor(format)}"

    /** Appends " (2)", " (3)"... before the extension until [desiredName] is not in [existingNames]. */
    fun resolveConflict(desiredName: String, existingNames: Set<String>): String {
        if (desiredName !in existingNames) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot >= 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot >= 0) desiredName.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "$base ($suffix)$ext"
            if (candidate !in existingNames) return candidate
            suffix++
        }
    }

    private fun sanitize(name: String): String = name.replace(ILLEGAL_CHARS, "_").trim().take(MAX_BASE_NAME_LENGTH)
}
