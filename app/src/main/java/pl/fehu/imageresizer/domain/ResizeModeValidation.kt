package pl.fehu.imageresizer.domain

import pl.fehu.imageresizer.domain.model.ResizeMode

/** Validation ranges for [ResizeMode] numeric inputs (application-plan.md §9.2). */
object ResizeModeValidation {
    const val MIN_PX = 1
    const val MAX_PX = 20000
    const val MIN_PERCENT = 1
    const val MAX_PERCENT_NO_UPSCALE = 100
    const val MAX_PERCENT_UPSCALE = 400
    const val MIN_MAX_KB = 10
    const val MAX_MAX_KB = 50000

    /** Null if [mode]'s numeric inputs are all in range; otherwise a human-readable reason. */
    fun errorFor(mode: ResizeMode, allowUpscale: Boolean): String? = when (mode) {
        is ResizeMode.OriginalSize -> null

        is ResizeMode.LongestEdge -> pxError(mode.px)
        is ResizeMode.ShortestEdge -> pxError(mode.px)

        is ResizeMode.ExactSize -> pxError(mode.width) ?: pxError(mode.height)

        is ResizeMode.Percentage -> {
            val max = if (allowUpscale) MAX_PERCENT_UPSCALE else MAX_PERCENT_NO_UPSCALE
            if (mode.percent !in MIN_PERCENT..max) "Must be $MIN_PERCENT–$max %" else null
        }

        is ResizeMode.TargetFileSize -> {
            if (mode.maxKb !in MIN_MAX_KB..MAX_MAX_KB) {
                "Must be $MIN_MAX_KB–$MAX_MAX_KB KB"
            } else {
                mode.maxLongestEdgePx?.let { pxError(it) }
            }
        }
    }

    private fun pxError(px: Int): String? = if (px !in MIN_PX..MAX_PX) "Must be $MIN_PX–$MAX_PX px" else null
}
