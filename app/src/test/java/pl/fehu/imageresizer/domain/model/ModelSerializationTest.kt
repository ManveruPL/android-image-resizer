package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSerializationTest {

    private val json = Json

    @Test
    fun jobConfig_roundTripsThroughJson_forEveryResizeMode() {
        val modes = listOf(
            ResizeMode.OriginalSize,
            ResizeMode.LongestEdge(1600),
            ResizeMode.ShortestEdge(1200),
            ResizeMode.ExactSize(800, 600, FitMode.FILL),
            ResizeMode.Percentage(50),
            ResizeMode.TargetFileSize(maxKb = 500, maxLongestEdgePx = 2000),
        )
        for (mode in modes) {
            val config = JobConfig(
                resize = mode,
                format = OutputFormat.JPEG,
                quality = 85,
                naming = NamingSpec(),
                conflict = ConflictPolicy.RENAME,
                metadata = MetadataPolicy.KEEP_ALL,
                inputFilter = setOf(InputFormat.HEIF),
                outputTreeUri = "content://tree/output",
            )
            val encoded = json.encodeToString(config)
            val decoded = json.decodeFromString<JobConfig>(encoded)
            assertEquals(config, decoded)
        }
    }

    @Test
    fun itemResult_roundTripsThroughJson_forEveryVariant() {
        val input = InputImage("content://img/1", "photo.jpg", "image/jpeg", InputFormat.JPEG, 12345L)
        val results: List<ItemResult> = listOf(
            ItemResult.Success(input, "photo_800x600.jpg", 800, 600, 12345L, 6000L),
            ItemResult.Skipped(input, "HEIC is not supported on this device"),
            ItemResult.Failed(input, "Out of memory"),
        )
        for (result in results) {
            val encoded = json.encodeToString(result)
            val decoded = json.decodeFromString<ItemResult>(encoded)
            assertEquals(result, decoded)
        }
    }

    @Test
    fun preset_roundTripsThroughJson() {
        val preset = Preset(
            id = "heic-to-jpeg",
            name = "HEIC to JPEG",
            config = JobConfig(
                resize = ResizeMode.OriginalSize,
                format = OutputFormat.JPEG,
                quality = 90,
                naming = NamingSpec(pattern = "{name}"),
                conflict = ConflictPolicy.RENAME,
                metadata = MetadataPolicy.KEEP_ALL,
                inputFilter = setOf(InputFormat.HEIF),
                outputTreeUri = "",
            ),
        )
        val encoded = json.encodeToString(preset)
        val decoded = json.decodeFromString<Preset>(encoded)
        assertEquals(preset, decoded)
    }
}
