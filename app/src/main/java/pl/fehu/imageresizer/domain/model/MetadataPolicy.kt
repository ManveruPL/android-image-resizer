package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MetadataPolicy { KEEP_ALL, STRIP_GPS, STRIP_ALL }
