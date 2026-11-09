package pl.fehu.imageresizer.domain

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySearchTest {

    /** Deterministic stand-in encoder: byte size grows linearly with quality and scale. */
    private fun encode(quality: Int, scale: Double): Int = (quality * scale * 10).roundToInt()

    /** Independent reference implementation (linear scan, not binary search) to cross-check against. */
    private fun bruteForce(maxBytes: Int): QualitySearch.Result {
        var scale = 1.0
        repeat(6) { attempt ->
            val best = (QualitySearch.MAX_QUALITY downTo QualitySearch.MIN_QUALITY)
                .map { quality -> quality to encode(quality, scale) }
                .firstOrNull { (_, bytes) -> bytes <= maxBytes }
            if (best != null) {
                return QualitySearch.Result(best.first, scale, best.second, targetReached = true)
            }
            if (attempt == 5) {
                return QualitySearch.Result(
                    QualitySearch.MIN_QUALITY,
                    scale,
                    encode(QualitySearch.MIN_QUALITY, scale),
                    targetReached = false,
                )
            }
            scale *= 0.85
        }
        error("unreachable")
    }

    @Test
    fun matchesBruteForceReferenceAcrossBudgets() {
        for (maxBytes in listOf(950, 700, 305, 300, 299, 250, 50, 1)) {
            assertEquals(bruteForce(maxBytes), QualitySearch.search(maxBytes, ::encode))
        }
    }

    @Test
    fun reachesTargetAtFullScaleWhenBudgetAllows() {
        val result = QualitySearch.search(700, ::encode)
        assertTrue(result.targetReached)
        assertEquals(1.0, result.scale, 0.0)
        assertTrue(result.bytes <= 700)
    }

    @Test
    fun fallsBackToScalingWhenMinQualityStillTooLarge_marksTargetNotReached() {
        val result = QualitySearch.search(1, ::encode)
        assertFalse(result.targetReached)
        assertEquals(QualitySearch.MIN_QUALITY, result.quality)
        assertEquals(Math.pow(0.85, 5.0), result.scale, 1e-9)
    }
}
