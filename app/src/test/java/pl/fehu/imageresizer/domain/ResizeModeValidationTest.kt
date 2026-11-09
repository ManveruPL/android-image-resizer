package pl.fehu.imageresizer.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.fehu.imageresizer.domain.model.FitMode
import pl.fehu.imageresizer.domain.model.ResizeMode

class ResizeModeValidationTest {

    @Test
    fun originalSize_neverInvalid() {
        assertNull(ResizeModeValidation.errorFor(ResizeMode.OriginalSize, allowUpscale = false))
    }

    @Test
    fun longestAndShortestEdge_boundsAreInclusive() {
        assertNull(ResizeModeValidation.errorFor(ResizeMode.LongestEdge(1), false))
        assertNull(ResizeModeValidation.errorFor(ResizeMode.LongestEdge(20000), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.LongestEdge(0), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.ShortestEdge(20001), false))
    }

    @Test
    fun exactSize_checksBothDimensions() {
        assertNull(ResizeModeValidation.errorFor(ResizeMode.ExactSize(800, 600, FitMode.FIT), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.ExactSize(0, 600, FitMode.FIT), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.ExactSize(800, 20001, FitMode.FIT), false))
    }

    @Test
    fun percentage_maxIs100WithoutUpscaleAnd400With() {
        assertNull(ResizeModeValidation.errorFor(ResizeMode.Percentage(100), allowUpscale = false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.Percentage(101), allowUpscale = false))
        assertNull(ResizeModeValidation.errorFor(ResizeMode.Percentage(400), allowUpscale = true))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.Percentage(401), allowUpscale = true))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.Percentage(0), allowUpscale = false))
    }

    @Test
    fun targetFileSize_checksMaxKbAndOptionalLongestEdge() {
        assertNull(ResizeModeValidation.errorFor(ResizeMode.TargetFileSize(500, null), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.TargetFileSize(9, null), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.TargetFileSize(50001, null), false))
        assertNull(ResizeModeValidation.errorFor(ResizeMode.TargetFileSize(500, 2000), false))
        assertNotNull(ResizeModeValidation.errorFor(ResizeMode.TargetFileSize(500, 20001), false))
    }
}
