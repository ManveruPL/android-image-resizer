package pl.fehu.imageresizer.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.OutputFormat

class FormatResolverTest {

    @Test
    fun explicitFormats_passThroughRegardlessOfInput() {
        for (input in InputFormat.entries) {
            assertEquals(OutputFormat.JPEG, FormatResolver.resolve(input, OutputFormat.JPEG))
            assertEquals(OutputFormat.PNG, FormatResolver.resolve(input, OutputFormat.PNG))
            assertEquals(OutputFormat.WEBP, FormatResolver.resolve(input, OutputFormat.WEBP))
        }
    }

    @Test
    fun keepOriginal_nativeFormatsPassThrough() {
        assertEquals(OutputFormat.JPEG, FormatResolver.resolve(InputFormat.JPEG, OutputFormat.KEEP_ORIGINAL))
        assertEquals(OutputFormat.PNG, FormatResolver.resolve(InputFormat.PNG, OutputFormat.KEEP_ORIGINAL))
        assertEquals(OutputFormat.WEBP, FormatResolver.resolve(InputFormat.WEBP, OutputFormat.KEEP_ORIGINAL))
    }

    @Test
    fun keepOriginal_unencodableFormatsMapToJpeg() {
        for (input in listOf(
            InputFormat.HEIF,
            InputFormat.AVIF,
            InputFormat.GIF,
            InputFormat.BMP,
            InputFormat.DNG,
            InputFormat.OTHER,
        )) {
            assertEquals(OutputFormat.JPEG, FormatResolver.resolve(input, OutputFormat.KEEP_ORIGINAL))
        }
    }

    @Test
    fun mimeTypeFor_mapsEachOutputFormat() {
        assertEquals("image/jpeg", FormatResolver.mimeTypeFor(OutputFormat.JPEG))
        assertEquals("image/png", FormatResolver.mimeTypeFor(OutputFormat.PNG))
        assertEquals("image/webp", FormatResolver.mimeTypeFor(OutputFormat.WEBP))
    }

    @Test
    fun mimeTypeFor_keepOriginal_throws() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FormatResolver.mimeTypeFor(OutputFormat.KEEP_ORIGINAL)
        }
    }

    @Test
    fun detect_recognizesEachMimeType() {
        assertEquals(InputFormat.JPEG, FormatResolver.detect("image/jpeg", "a.jpg"))
        assertEquals(InputFormat.PNG, FormatResolver.detect("image/png", "a.png"))
        assertEquals(InputFormat.WEBP, FormatResolver.detect("image/webp", "a.webp"))
        assertEquals(InputFormat.HEIF, FormatResolver.detect("image/heic", "a.heic"))
        assertEquals(InputFormat.HEIF, FormatResolver.detect("image/heif", "a.heif"))
        assertEquals(InputFormat.AVIF, FormatResolver.detect("image/avif", "a.avif"))
        assertEquals(InputFormat.GIF, FormatResolver.detect("image/gif", "a.gif"))
        assertEquals(InputFormat.BMP, FormatResolver.detect("image/bmp", "a.bmp"))
        assertEquals(InputFormat.DNG, FormatResolver.detect("image/x-adobe-dng", "a.dng"))
    }

    @Test
    fun detect_recognizesAlternateMimeSpellings() {
        assertEquals(InputFormat.JPEG, FormatResolver.detect("image/jpg", "a.jpg"))
        assertEquals(InputFormat.BMP, FormatResolver.detect("image/x-ms-bmp", "a.bmp"))
    }

    @Test
    fun detect_fallsBackToExtension_whenMimeIsGenericOctetStream() {
        assertEquals(InputFormat.HEIF, FormatResolver.detect("application/octet-stream", "IMG_0001.heic"))
    }

    @Test
    fun detect_extensionIsCaseInsensitive() {
        assertEquals(InputFormat.HEIF, FormatResolver.detect(null, "IMG_0001.HEIC"))
        assertEquals(InputFormat.JPEG, FormatResolver.detect(null, "PHOTO.JPG"))
    }

    @Test
    fun detect_everyKnownExtension() {
        assertEquals(InputFormat.JPEG, FormatResolver.detect(null, "a.jpg"))
        assertEquals(InputFormat.JPEG, FormatResolver.detect(null, "a.jpeg"))
        assertEquals(InputFormat.PNG, FormatResolver.detect(null, "a.png"))
        assertEquals(InputFormat.WEBP, FormatResolver.detect(null, "a.webp"))
        assertEquals(InputFormat.HEIF, FormatResolver.detect(null, "a.heic"))
        assertEquals(InputFormat.HEIF, FormatResolver.detect(null, "a.heif"))
        assertEquals(InputFormat.AVIF, FormatResolver.detect(null, "a.avif"))
        assertEquals(InputFormat.GIF, FormatResolver.detect(null, "a.gif"))
        assertEquals(InputFormat.BMP, FormatResolver.detect(null, "a.bmp"))
        assertEquals(InputFormat.DNG, FormatResolver.detect(null, "a.dng"))
    }

    @Test
    fun detect_unknownMimeAndExtension_isOther() {
        assertEquals(InputFormat.OTHER, FormatResolver.detect("application/pdf", "a.pdf"))
    }

    @Test
    fun detect_noMimeNoExtension_isOther() {
        assertEquals(InputFormat.OTHER, FormatResolver.detect(null, "noextension"))
    }
}
