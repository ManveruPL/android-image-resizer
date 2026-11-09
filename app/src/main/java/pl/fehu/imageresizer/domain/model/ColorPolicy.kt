package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ColorPolicy { CONVERT_TO_SRGB, KEEP_SOURCE }
