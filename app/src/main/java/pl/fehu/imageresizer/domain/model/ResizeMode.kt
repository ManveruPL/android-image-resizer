package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface ResizeMode {
    /** Convert only, no resize. */
    @Serializable
    data object OriginalSize : ResizeMode

    @Serializable
    data class LongestEdge(val px: Int) : ResizeMode

    @Serializable
    data class ShortestEdge(val px: Int) : ResizeMode

    @Serializable
    data class ExactSize(val width: Int, val height: Int, val fit: FitMode) : ResizeMode

    /** 1..100, or up to 400 when upscaling is allowed. */
    @Serializable
    data class Percentage(val percent: Int) : ResizeMode

    @Serializable
    data class TargetFileSize(val maxKb: Int, val maxLongestEdgePx: Int?) : ResizeMode
}

@Serializable
enum class FitMode {
    /** Scale to fit inside the box, keep ratio. */
    FIT,

    /** Scale to cover the box, keep ratio, center-crop. */
    FILL,

    /** Scale width and height independently to the exact box; ratio not kept. */
    STRETCH,
}
