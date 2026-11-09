package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface ItemResult {
    val input: InputImage

    @Serializable
    data class Success(
        override val input: InputImage,
        val outputName: String,
        val outWidth: Int,
        val outHeight: Int,
        val bytesBefore: Long?,
        val bytesAfter: Long,
        /** e.g. "first frame only (animated source)" or "target size not reached"; see [pl.fehu.imageresizer.engine.ProcessedImage.note]. */
        val note: String? = null,
    ) : ItemResult

    @Serializable
    data class Skipped(override val input: InputImage, val reason: String) : ItemResult

    @Serializable
    data class Failed(override val input: InputImage, val error: String) : ItemResult
}
