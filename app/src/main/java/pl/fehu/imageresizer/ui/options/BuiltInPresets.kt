package pl.fehu.imageresizer.ui.options

import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.Preset
import pl.fehu.imageresizer.domain.model.ResizeMode

/**
 * The five built-in presets from application-plan.md §8, hardcoded here for M5. `PresetRepository`
 * (M6) owns seeding these on first launch and lets the user delete/rename them and add their own;
 * until then, applying one of these only changes [JobDraftViewModel]'s in-memory config (see
 * DECISIONS.md) -- it is never persisted and does not survive the process dying.
 *
 * [Preset.config]'s `outputTreeUri` is blank: applying a preset never changes the output folder
 * (callers must preserve the current one, per [Preset]'s own kdoc).
 */
object BuiltInPresets {

    private fun base(
        resize: ResizeMode,
        format: OutputFormat,
        quality: Int,
        naming: NamingSpec = NamingSpec(),
        inputFilter: Set<InputFormat> = emptySet(),
    ) = JobConfig(
        resize = resize,
        format = format,
        quality = quality,
        naming = naming,
        conflict = ConflictPolicy.RENAME,
        metadata = MetadataPolicy.KEEP_ALL,
        color = ColorPolicy.CONVERT_TO_SRGB,
        inputFilter = inputFilter,
        outputTreeUri = "",
    )

    val heicToJpeg = Preset(
        id = "heic-to-jpeg",
        name = "HEIC → JPEG",
        config = base(
            resize = ResizeMode.OriginalSize,
            format = OutputFormat.JPEG,
            quality = 90,
            naming = NamingSpec(pattern = "{name}"),
            inputFilter = setOf(InputFormat.HEIF),
        ),
    )

    val web1600 = Preset(
        id = "web-1600",
        name = "Web 1600px JPEG 82",
        config = base(resize = ResizeMode.LongestEdge(1600), format = OutputFormat.JPEG, quality = 82),
    )

    val shopProduct1200 = Preset(
        id = "shop-product-1200",
        name = "Shop product 1200px WebP 80",
        config = base(resize = ResizeMode.LongestEdge(1200), format = OutputFormat.WEBP, quality = 80),
    )

    val email1024 = Preset(
        id = "email-1024",
        name = "Email 1024px JPEG 75",
        config = base(resize = ResizeMode.LongestEdge(1024), format = OutputFormat.JPEG, quality = 75),
    )

    val under500Kb = Preset(
        id = "under-500kb",
        name = "Under 500 KB JPEG",
        config = base(resize = ResizeMode.TargetFileSize(maxKb = 500, maxLongestEdgePx = null), format = OutputFormat.JPEG, quality = 85),
    )

    val all: List<Preset> = listOf(heicToJpeg, web1600, shopProduct1200, email1024, under500Kb)
}
