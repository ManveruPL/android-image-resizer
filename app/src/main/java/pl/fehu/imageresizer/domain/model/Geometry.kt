package pl.fehu.imageresizer.domain.model

/** Pure domain replacement for `android.graphics.Rect`; right/bottom are exclusive. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
