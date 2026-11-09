package pl.fehu.imageresizer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import pl.fehu.imageresizer.domain.model.OutputFormat

class FileNamerTest {

    @Test
    fun allTokensAreSubstituted() {
        val name = FileNamer.buildBaseName(
            pattern = "{name}_{w}x{h}_{index}_{date}_{preset}",
            originalName = "photo.jpg",
            width = 800,
            height = 600,
            index = 3,
            batchSize = 120,
            date = "20261017",
            presetName = "Web",
        )
        assertEquals("photo_800x600_003_20261017_Web", name)
    }

    @Test
    fun missingPresetName_substitutesEmpty() {
        val name = FileNamer.buildBaseName(
            pattern = "{name}_{preset}",
            originalName = "photo.jpg",
            width = 800,
            height = 600,
            index = 1,
            batchSize = 1,
            date = "20261017",
            presetName = null,
        )
        assertEquals("photo_", name)
    }

    @Test
    fun indexIsZeroPaddedToBatchSizeDigits() {
        val name = FileNamer.buildBaseName(
            pattern = "{index}",
            originalName = "photo.jpg",
            width = 1,
            height = 1,
            index = 7,
            batchSize = 1000,
            date = "20261017",
            presetName = null,
        )
        assertEquals("0007", name)
    }

    @Test
    fun originalNameWithoutExtension_dropsExtensionOnly() {
        val name = FileNamer.buildBaseName(
            pattern = "{name}",
            originalName = "my.photo.final.jpeg",
            width = 1,
            height = 1,
            index = 1,
            batchSize = 1,
            date = "20261017",
            presetName = null,
        )
        assertEquals("my.photo.final", name)
    }

    @Test
    fun illegalCharactersAreReplaced() {
        val name = FileNamer.buildBaseName(
            pattern = "{name}",
            originalName = "my:file*name?.jpg",
            width = 1,
            height = 1,
            index = 1,
            batchSize = 1,
            date = "20261017",
            presetName = null,
        )
        assertEquals("my_file_name_", name)
    }

    @Test
    fun emptyResult_fallsBackToImageIndex() {
        val name = FileNamer.buildBaseName(
            pattern = "{name}",
            originalName = ".jpg",
            width = 1,
            height = 1,
            index = 5,
            batchSize = 9,
            date = "20261017",
            presetName = null,
        )
        assertEquals("image_5", name)
    }

    @Test
    fun resultIsTruncatedTo120Characters() {
        val longName = "a".repeat(150) + ".jpg"
        val name = FileNamer.buildBaseName(
            pattern = "{name}",
            originalName = longName,
            width = 1,
            height = 1,
            index = 1,
            batchSize = 1,
            date = "20261017",
            presetName = null,
        )
        assertEquals(120, name.length)
        assertEquals("a".repeat(120), name)
    }

    @Test
    fun extensionFor_mapsEachOutputFormat() {
        assertEquals("jpg", FileNamer.extensionFor(OutputFormat.JPEG))
        assertEquals("png", FileNamer.extensionFor(OutputFormat.PNG))
        assertEquals("webp", FileNamer.extensionFor(OutputFormat.WEBP))
    }

    @Test
    fun extensionFor_keepOriginal_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            FileNamer.extensionFor(OutputFormat.KEEP_ORIGINAL)
        }
    }

    @Test
    fun withExtension_appendsDotAndExtension() {
        assertEquals("photo.jpg", FileNamer.withExtension("photo", OutputFormat.JPEG))
    }

    @Test
    fun resolveConflict_returnsUnchangedWhenNameIsFree() {
        assertEquals("photo.jpg", FileNamer.resolveConflict("photo.jpg", emptySet()))
    }

    @Test
    fun resolveConflict_appendsIncrementingSuffix() {
        val existing = setOf("photo.jpg", "photo (2).jpg")
        assertEquals("photo (3).jpg", FileNamer.resolveConflict("photo.jpg", existing))
    }

    @Test
    fun resolveConflict_handlesNameWithoutExtension() {
        assertEquals("noext (2)", FileNamer.resolveConflict("noext", setOf("noext")))
    }
}
