package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class OutputFormat { KEEP_ORIGINAL, JPEG, PNG, WEBP }
