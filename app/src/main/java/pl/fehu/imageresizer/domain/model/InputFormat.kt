package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

/** Detected from MIME type, falling back to the file extension. */
@Serializable
enum class InputFormat { JPEG, PNG, WEBP, HEIF, AVIF, GIF, BMP, DNG, OTHER }
