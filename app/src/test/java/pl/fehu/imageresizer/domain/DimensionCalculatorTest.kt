package pl.fehu.imageresizer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import pl.fehu.imageresizer.domain.model.FitMode
import pl.fehu.imageresizer.domain.model.Rect
import pl.fehu.imageresizer.domain.model.ResizeMode

class DimensionCalculatorTest {

    @Test
    fun originalSize_noResizeNoCrop() {
        val dims = DimensionCalculator.compute(800, 600, ResizeMode.OriginalSize, allowUpscale = false)
        assertEquals(800, dims.decodeWidth)
        assertEquals(600, dims.decodeHeight)
        assertNull(dims.cropRect)
        assertEquals(800, dims.finalWidth)
        assertEquals(600, dims.finalHeight)
    }

    @Test
    fun longestEdge_downscalesLandscape() {
        val dims = DimensionCalculator.compute(1000, 500, ResizeMode.LongestEdge(500), allowUpscale = false)
        assertEquals(500, dims.decodeWidth)
        assertEquals(250, dims.decodeHeight)
    }

    @Test
    fun longestEdge_portrait() {
        val dims = DimensionCalculator.compute(500, 1000, ResizeMode.LongestEdge(500), allowUpscale = false)
        assertEquals(250, dims.decodeWidth)
        assertEquals(500, dims.decodeHeight)
    }

    @Test
    fun longestEdge_square() {
        val dims = DimensionCalculator.compute(500, 500, ResizeMode.LongestEdge(250), allowUpscale = false)
        assertEquals(250, dims.decodeWidth)
        assertEquals(250, dims.decodeHeight)
    }

    @Test
    fun longestEdge_upscaleDisallowed_clampsToSource() {
        val dims = DimensionCalculator.compute(100, 50, ResizeMode.LongestEdge(500), allowUpscale = false)
        assertEquals(100, dims.decodeWidth)
        assertEquals(50, dims.decodeHeight)
    }

    @Test
    fun longestEdge_upscaleAllowed_scalesUp() {
        val dims = DimensionCalculator.compute(100, 50, ResizeMode.LongestEdge(500), allowUpscale = true)
        assertEquals(500, dims.decodeWidth)
        assertEquals(250, dims.decodeHeight)
    }

    @Test
    fun shortestEdge_scalesByMinSide() {
        val dims = DimensionCalculator.compute(1000, 500, ResizeMode.ShortestEdge(250), allowUpscale = false)
        assertEquals(500, dims.decodeWidth)
        assertEquals(250, dims.decodeHeight)
    }

    @Test
    fun percentage_downscale() {
        val dims = DimensionCalculator.compute(800, 600, ResizeMode.Percentage(50), allowUpscale = false)
        assertEquals(400, dims.decodeWidth)
        assertEquals(300, dims.decodeHeight)
    }

    @Test
    fun percentage_upscaleDisallowed_clampsTo100() {
        val dims = DimensionCalculator.compute(800, 600, ResizeMode.Percentage(200), allowUpscale = false)
        assertEquals(800, dims.decodeWidth)
        assertEquals(600, dims.decodeHeight)
    }

    @Test
    fun percentage_upscaleAllowed() {
        val dims = DimensionCalculator.compute(800, 600, ResizeMode.Percentage(200), allowUpscale = true)
        assertEquals(1600, dims.decodeWidth)
        assertEquals(1200, dims.decodeHeight)
    }

    @Test
    fun exactSizeFit_keepsRatioInsideBox() {
        val dims = DimensionCalculator.compute(
            1000,
            500,
            ResizeMode.ExactSize(400, 400, FitMode.FIT),
            allowUpscale = false,
        )
        assertEquals(400, dims.decodeWidth)
        assertEquals(200, dims.decodeHeight)
        assertNull(dims.cropRect)
    }

    @Test
    fun exactSizeFit_upscaleDisallowed_outputsSourceSize() {
        val dims = DimensionCalculator.compute(
            100,
            50,
            ResizeMode.ExactSize(400, 400, FitMode.FIT),
            allowUpscale = false,
        )
        assertEquals(100, dims.decodeWidth)
        assertEquals(50, dims.decodeHeight)
    }

    @Test
    fun exactSizeFit_upscaleAllowed() {
        val dims = DimensionCalculator.compute(
            100,
            50,
            ResizeMode.ExactSize(400, 400, FitMode.FIT),
            allowUpscale = true,
        )
        assertEquals(400, dims.decodeWidth)
        assertEquals(200, dims.decodeHeight)
    }

    @Test
    fun exactSizeStretch_ignoresRatioAndUpscaleFlag() {
        val dims = DimensionCalculator.compute(
            1000,
            500,
            ResizeMode.ExactSize(300, 300, FitMode.STRETCH),
            allowUpscale = false,
        )
        assertEquals(300, dims.decodeWidth)
        assertEquals(300, dims.decodeHeight)
        assertNull(dims.cropRect)
        assertEquals(300, dims.finalWidth)
        assertEquals(300, dims.finalHeight)
    }

    @Test
    fun exactSizeFill_coverAndCenterCrop_withUpscale() {
        val dims = DimensionCalculator.compute(
            2000,
            1000,
            ResizeMode.ExactSize(400, 300, FitMode.FILL),
            allowUpscale = true,
        )
        assertEquals(600, dims.decodeWidth)
        assertEquals(300, dims.decodeHeight)
        assertEquals(Rect(100, 0, 500, 300), dims.cropRect)
        assertEquals(400, dims.finalWidth)
        assertEquals(300, dims.finalHeight)
    }

    @Test
    fun exactSizeFill_sourceAlreadyCovers_noUpscaleNeeded() {
        // rawScale (0.3) <= 1.0, so the no-upscale path behaves the same as the upscale-allowed path.
        val dims = DimensionCalculator.compute(
            2000,
            1000,
            ResizeMode.ExactSize(400, 300, FitMode.FILL),
            allowUpscale = false,
        )
        assertEquals(600, dims.decodeWidth)
        assertEquals(300, dims.decodeHeight)
        assertEquals(Rect(100, 0, 500, 300), dims.cropRect)
    }

    @Test
    fun exactSizeFill_upscaleDisallowed_widthLimited_cropsLargestRatioRect() {
        val dims = DimensionCalculator.compute(
            100,
            50,
            ResizeMode.ExactSize(400, 300, FitMode.FILL),
            allowUpscale = false,
        )
        // Source is wider than the 4:3 box ratio, so height is the limiting side.
        assertEquals(100, dims.decodeWidth)
        assertEquals(50, dims.decodeHeight)
        assertEquals(Rect(16, 0, 83, 50), dims.cropRect)
        assertEquals(67, dims.finalWidth)
        assertEquals(50, dims.finalHeight)
    }

    @Test
    fun exactSizeFill_upscaleDisallowed_heightLimited_cropsLargestRatioRect() {
        val dims = DimensionCalculator.compute(
            50,
            100,
            ResizeMode.ExactSize(300, 400, FitMode.FILL),
            allowUpscale = false,
        )
        // Source is narrower than the 3:4 box ratio, so width is the limiting side.
        assertEquals(50, dims.decodeWidth)
        assertEquals(100, dims.decodeHeight)
        assertEquals(Rect(0, 16, 50, 83), dims.cropRect)
        assertEquals(50, dims.finalWidth)
        assertEquals(67, dims.finalHeight)
    }

    @Test
    fun targetFileSize_withMaxLongestEdge_scalesDimensions() {
        val dims = DimensionCalculator.compute(
            2000,
            1000,
            ResizeMode.TargetFileSize(maxKb = 500, maxLongestEdgePx = 800),
            allowUpscale = false,
        )
        assertEquals(800, dims.decodeWidth)
        assertEquals(400, dims.decodeHeight)
    }

    @Test
    fun targetFileSize_withoutMaxLongestEdge_keepsOriginalDimensions() {
        val dims = DimensionCalculator.compute(
            2000,
            1000,
            ResizeMode.TargetFileSize(maxKb = 500, maxLongestEdgePx = null),
            allowUpscale = false,
        )
        assertEquals(2000, dims.decodeWidth)
        assertEquals(1000, dims.decodeHeight)
    }

    @Test
    fun oneByOnePixelSource() {
        val dims = DimensionCalculator.compute(1, 1, ResizeMode.LongestEdge(500), allowUpscale = false)
        assertEquals(1, dims.decodeWidth)
        assertEquals(1, dims.decodeHeight)
    }

    @Test
    fun oneByOnePixelSource_upscaled() {
        val dims = DimensionCalculator.compute(1, 1, ResizeMode.LongestEdge(500), allowUpscale = true)
        assertEquals(500, dims.decodeWidth)
        assertEquals(500, dims.decodeHeight)
    }

    @Test
    fun veryLargeSource() {
        val dims = DimensionCalculator.compute(20000, 20000, ResizeMode.Percentage(10), allowUpscale = false)
        assertEquals(2000, dims.decodeWidth)
        assertEquals(2000, dims.decodeHeight)
    }

    @Test
    fun scaleRoundsToAtLeastOnePixel() {
        val dims = DimensionCalculator.compute(3, 1, ResizeMode.Percentage(1), allowUpscale = false)
        assertEquals(1, dims.decodeWidth)
        assertEquals(1, dims.decodeHeight)
    }

    @Test
    fun nonPositiveSourceDimensions_throw() {
        assertThrows(IllegalArgumentException::class.java) {
            DimensionCalculator.compute(0, 100, ResizeMode.OriginalSize, allowUpscale = false)
        }
    }
}
