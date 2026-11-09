package pl.fehu.imageresizer.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NamingSpec(val pattern: String = "{name}_{w}x{h}")
