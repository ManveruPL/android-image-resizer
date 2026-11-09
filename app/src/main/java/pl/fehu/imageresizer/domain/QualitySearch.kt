package pl.fehu.imageresizer.domain

/**
 * Binary-searches encode quality to hit a byte budget, scaling dimensions down and retrying when
 * even the minimum quality is too large. See application-plan.md §5.3. [encode] is injected so
 * this is testable without real bitmaps.
 */
object QualitySearch {

    const val MIN_QUALITY = 30
    const val MAX_QUALITY = 95
    private const val MAX_ITERATIONS = 7
    private const val MAX_SCALE_RETRIES = 5
    private const val SCALE_STEP = 0.85

    data class Result(val quality: Int, val scale: Double, val bytes: Int, val targetReached: Boolean)

    /** [encode] returns the encoded byte size for a given quality and dimension scale. */
    fun search(maxBytes: Int, encode: (quality: Int, scale: Double) -> Int): Result {
        var scale = 1.0
        for (attempt in 0..MAX_SCALE_RETRIES) {
            val found = binarySearchQuality(maxBytes, scale, encode)
            if (found != null) {
                return Result(found.first, scale, found.second, targetReached = true)
            }
            if (attempt == MAX_SCALE_RETRIES) {
                val bytes = encode(MIN_QUALITY, scale)
                return Result(MIN_QUALITY, scale, bytes, targetReached = false)
            }
            scale *= SCALE_STEP
        }
        error("unreachable")
    }

    /** Returns the highest quality whose encoded size is <= [maxBytes], or null if none qualifies. */
    private fun binarySearchQuality(
        maxBytes: Int,
        scale: Double,
        encode: (Int, Double) -> Int,
    ): Pair<Int, Int>? {
        var lo = MIN_QUALITY
        var hi = MAX_QUALITY
        var best: Pair<Int, Int>? = null
        repeat(MAX_ITERATIONS) {
            if (lo <= hi) {
                val mid = (lo + hi) / 2
                val bytes = encode(mid, scale)
                if (bytes <= maxBytes) {
                    best = mid to bytes
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
        }
        return best
    }
}
