package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class InputImage(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val format: InputFormat,
    val sizeBytes: Long?,
)
