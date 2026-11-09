package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class JobConfig(
    val resize: ResizeMode,
    val format: OutputFormat,
    /** 1..100, ignored for PNG. */
    val quality: Int,
    val allowUpscale: Boolean = false,
    val naming: NamingSpec,
    val conflict: ConflictPolicy,
    val metadata: MetadataPolicy,
    val color: ColorPolicy = ColorPolicy.CONVERT_TO_SRGB,
    /** Empty means all formats. */
    val inputFilter: Set<InputFormat> = emptySet(),
    val outputTreeUri: String,
)
