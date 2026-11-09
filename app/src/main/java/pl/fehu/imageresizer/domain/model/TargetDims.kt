package pl.fehu.imageresizer.domain.model

/**
 * Output of [pl.fehu.imageresizer.domain.DimensionCalculator.compute].
 *
 * [decodeWidth]/[decodeHeight] is the size to pass to the decoder (`setTargetSize`); [cropRect],
 * when present, is in that decoded bitmap's coordinates. [finalWidth]/[finalHeight] is the size
 * of the resulting image after any crop is applied.
 */
data class TargetDims(
    val decodeWidth: Int,
    val decodeHeight: Int,
    val cropRect: Rect?,
    val finalWidth: Int,
    val finalHeight: Int,
)
