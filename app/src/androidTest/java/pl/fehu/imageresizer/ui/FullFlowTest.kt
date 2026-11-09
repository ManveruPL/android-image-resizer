package pl.fehu.imageresizer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.App
import pl.fehu.imageresizer.FakeContentProvider
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.ui.home.HomeScreen
import pl.fehu.imageresizer.ui.home.JobDraftViewModel
import pl.fehu.imageresizer.ui.progress.ProgressScreen
import pl.fehu.imageresizer.ui.progress.ProgressViewModel
import pl.fehu.imageresizer.ui.results.ResultsScreen
import pl.fehu.imageresizer.ui.results.ResultsViewModel
import pl.fehu.imageresizer.ui.theme.BatchResizerTheme

/**
 * Drives the real pipeline Home -> start -> Progress -> Results end to end (application-plan.md
 * M5's "full flow on emulator from picking to results" AC), through the same [App] container
 * `ResizeWorker` itself resolves, so the real `JobScheduler`/WorkManager/`ResizeWorker`/
 * `BatchRunner` chain actually runs (not a fake). "Picking" is a [FakeContentProvider] URI fed
 * directly to the view model -- the system photo/file/folder pickers can't be driven without a
 * UI-automator dependency (see DECISIONS.md). The output folder is a syntactically valid but
 * unwritable URI, so the item ends up `Failed` rather than `Success`; that still exercises the
 * full screen-to-screen flow and proves the pipeline never crashes on a bad folder (hard rule 6).
 */
@RunWith(AndroidJUnit4::class)
class FullFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container by lazy { (context.applicationContext as App).container }

    @Test
    fun homeToProgressToResults_withoutCrashing() {
        // Defensive cleanup: ExistingWorkPolicy.KEEP means a leftover run from a previous test
        // (or manual session) on this persistent emulator would otherwise silently swallow ours.
        container.jobScheduler.cancel()

        // An isolated settings/preset store, not the real app's: this test's fake output folder
        // and image selection must not leak into the real app's persisted settings (M6).
        val testDataStore = newTestPreferencesDataStore(context)
        val homeViewModel = JobDraftViewModel(
            savedStateHandle = SavedStateHandle(),
            inputResolver = container.inputResolver,
            uriPermissionManager = container.uriPermissionManager,
            jobScheduler = container.jobScheduler,
            decoder = Decoder(context.contentResolver),
            settingsRepository = SettingsRepository(testDataStore),
            presetRepository = PresetRepository(testDataStore),
        )

        lateinit var currentScreen: androidx.compose.runtime.MutableState<Screen>
        composeRule.setContent {
            BatchResizerTheme {
                val screen = remember { mutableStateOf(Screen.HOME) }
                currentScreen = screen
                when (screen.value) {
                    Screen.HOME -> HomeScreen(homeViewModel, onOpenOptions = {}, onStarted = { screen.value = Screen.PROGRESS })
                    Screen.PROGRESS -> ProgressScreen(
                        viewModel = remember { ProgressViewModel(container.jobScheduler, container.jobStore) },
                        onBack = {},
                        onFinished = { screen.value = Screen.RESULTS },
                    )
                    Screen.RESULTS -> ResultsScreen(
                        viewModel = remember { ResultsViewModel(container.jobStore, container.jobScheduler, container.appContext) },
                        onBack = {},
                        onRetryStarted = {},
                    )
                }
            }
        }

        val imageUri = FakeContentProvider.register(context, "full_flow.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val fakeOutputTree = FakeContentProvider.treeUriFor(java.io.File(context.cacheDir, "full_flow_output_${System.nanoTime()}"))
        composeRule.runOnUiThread {
            homeViewModel.addUris(listOf(imageUri))
            homeViewModel.setOutputFolder(fakeOutputTree, "full_flow_output")
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            homeViewModel.selectedImages.value.isNotEmpty() && homeViewModel.outputTreeUri.value == fakeOutputTree
        }
        composeRule.waitForIdle()

        val startLabel = context.getString(R.string.home_start_button, 1)
        composeRule.onNodeWithText(startLabel).performClick()

        // Progress screen should appear, then auto-advance to Results once the real worker finishes.
        composeRule.waitUntil(timeoutMillis = 30_000) { currentScreen.value == Screen.RESULTS }

        composeRule.onNodeWithText(context.getString(R.string.results_totals, 0, 1, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.results_retry_failed)).assertIsDisplayed()
    }
}

private enum class Screen { HOME, PROGRESS, RESULTS }
