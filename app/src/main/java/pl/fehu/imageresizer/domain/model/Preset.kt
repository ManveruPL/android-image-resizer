package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

/** [config]'s `outputTreeUri` may be blank: applying a preset never changes the output folder. */
@Serializable
data class Preset(val id: String, val name: String, val config: JobConfig)
