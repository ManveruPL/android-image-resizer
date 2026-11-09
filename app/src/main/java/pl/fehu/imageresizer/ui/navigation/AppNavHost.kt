package pl.fehu.imageresizer.ui.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pl.fehu.imageresizer.App
import pl.fehu.imageresizer.ui.home.HomeScreen
import pl.fehu.imageresizer.ui.home.JobDraftViewModel
import pl.fehu.imageresizer.ui.options.OptionsScreen
import pl.fehu.imageresizer.ui.progress.ProgressScreen
import pl.fehu.imageresizer.ui.progress.ProgressViewModel
import pl.fehu.imageresizer.ui.results.ResultsScreen
import pl.fehu.imageresizer.ui.results.ResultsViewModel

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController(), shareIntent: Intent? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val jobDraftViewModel: JobDraftViewModel = viewModel(factory = JobDraftViewModel.factory(container))

    LaunchedEffect(shareIntent) {
        shareIntent?.let(jobDraftViewModel::handleShareIntent)
    }

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel = jobDraftViewModel,
                onOpenOptions = { navController.navigate(OptionsRoute) },
                onStarted = {
                    navController.navigate(ProgressRoute) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }
        composable<OptionsRoute> {
            OptionsScreen(viewModel = jobDraftViewModel, onBack = navController::navigateUp)
        }
        composable<ProgressRoute> {
            val progressViewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.factory(container))
            ProgressScreen(
                viewModel = progressViewModel,
                onBack = { navController.popBackStack(HomeRoute, inclusive = false) },
                onFinished = {
                    navController.navigate(ResultsRoute) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }
        composable<ResultsRoute> {
            val resultsViewModel: ResultsViewModel = viewModel(factory = ResultsViewModel.factory(container))
            ResultsScreen(
                viewModel = resultsViewModel,
                onBack = { navController.popBackStack(HomeRoute, inclusive = false) },
                onRetryStarted = {
                    navController.navigate(ProgressRoute) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }
    }
}
