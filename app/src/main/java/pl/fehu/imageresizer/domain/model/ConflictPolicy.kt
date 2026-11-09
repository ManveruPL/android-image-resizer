package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConflictPolicy { RENAME, SKIP, OVERWRITE }
