package pl.fehu.imageresizer.domain

import kotlin.math.roundToInt
import pl.fehu.imageresizer.domain.model.FitMode
import pl.fehu.imageresizer.domain.model.Rect
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.domain.model.TargetDims

/**
 * Computes decode and output dimensions for a resize mode. Pure function of source size, mode
 * and the upscale flag; see application-plan.md §5.1 for the rules it implements.
 */
object DimensionCalculator {

    fun compute(srcW: Int, srcH: Int, mode: ResizeMode, allowUpscale: Boolean): TargetDims {
        require(srcW > 0 && srcH > 0) { "source dimensions must be positive: ${srcW}x$srcH" }
        return when (mode) {
            is ResizeMode.OriginalSize -> noCrop(srcW, srcH, scale = 1.0)

            is ResizeMode.LongestEdge -> {
                val scale = clampScale(mode.px.toDouble() / maxOf(srcW, srcH), allowUpscale)
                noCrop(srcW, srcH, scale)
            }

            is ResizeMode.ShortestEdge -> {
                val scale = clampScale(mode.px.toDouble() / minOf(srcW, srcH), allowUpscale)
                noCrop(srcW, srcH, scale)
            }

            is ResizeMode.Percentage -> {
                val scale = clampScale(mode.percent / 100.0, allowUpscale)
                noCrop(srcW, srcH, scale)
            }

            is ResizeMode.TargetFileSize -> {
                val longest = mode.maxLongestEdgePx
                if (longest == null) {
                    noCrop(srcW, srcH, scale = 1.0)
                } else {
                    val scale = clampScale(longest.toDouble() / maxOf(srcW, srcH), allowUpscale)
                    noCrop(srcW, srcH, scale)
                }
            }

            is ResizeMode.ExactSize -> exactSize(srcW, srcH, mode.width, mode.height, mode.fit, allowUpscale)
        }
    }

    private fun exactSize(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
        fit: FitMode,
        allowUpscale: Boolean,
    ): TargetDims = when (fit) {
        FitMode.FIT -> {
            val scale = clampScale(minOf(targetW.toDouble() / srcW, targetH.toDouble() / srcH), allowUpscale)
            noCrop(srcW, srcH, scale)
        }

        // Stretch always honors the exact box; the user accepted ratio distortion by choosing it.
        FitMode.STRETCH -> {
            val w = maxOf(1, targetW)
            val h = maxOf(1, targetH)
            TargetDims(decodeWidth = w, decodeHeight = h, cropRect = null, finalWidth = w, finalHeight = h)
        }

        FitMode.FILL -> fillCrop(srcW, srcH, targetW, targetH, allowUpscale)
    }

    private fun fillCrop(srcW: Int, srcH: Int, targetW: Int, targetH: Int, allowUpscale: Boolean): TargetDims {
        val rawScale = maxOf(targetW.toDouble() / srcW, targetH.toDouble() / srcH)
        if (allowUpscale || rawScale <= 1.0) {
            val decodeW = scaleDim(srcW, rawScale)
            val decodeH = scaleDim(srcH, rawScale)
            val cropW = minOf(targetW, decodeW)
            val cropH = minOf(targetH, decodeH)
            return TargetDims(decodeW, decodeH, centeredCrop(decodeW, decodeH, cropW, cropH), cropW, cropH)
        }

        // Upscale disallowed and the source is too small to cover the box: keep the source size
        // and crop the largest rectangle that matches the box's aspect ratio.
        val ratio = targetW.toDouble() / targetH
        val cropW: Int
        val cropH: Int
        if (srcW.toDouble() / srcH > ratio) {
            cropH = srcH
            cropW = maxOf(1, (srcH * ratio).roundToInt())
        } else {
            cropW = srcW
            cropH = maxOf(1, (srcW / ratio).roundToInt())
        }
        return TargetDims(srcW, srcH, centeredCrop(srcW, srcH, cropW, cropH), cropW, cropH)
    }

    private fun centeredCrop(containerW: Int, containerH: Int, cropW: Int, cropH: Int): Rect {
        val left = (containerW - cropW) / 2
        val top = (containerH - cropH) / 2
        return Rect(left, top, left + cropW, top + cropH)
    }

    private fun clampScale(rawScale: Double, allowUpscale: Boolean): Double =
        if (!allowUpscale) minOf(rawScale, 1.0) else rawScale

    private fun scaleDim(dim: Int, scale: Double): Int = maxOf(1, (dim * scale).roundToInt())

    private fun noCrop(srcW: Int, srcH: Int, scale: Double): TargetDims {
        val w = scaleDim(srcW, scale)
        val h = scaleDim(srcH, scale)
        return TargetDims(decodeWidth = w, decodeHeight = h, cropRect = null, finalWidth = w, finalHeight = h)
    }
}
