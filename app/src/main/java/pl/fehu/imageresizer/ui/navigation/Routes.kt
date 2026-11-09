package pl.fehu.imageresizer.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations. */
@Serializable
data object HomeRoute

@Serializable
data object OptionsRoute

@Serializable
data object ProgressRoute

@Serializable
data object ResultsRoute
