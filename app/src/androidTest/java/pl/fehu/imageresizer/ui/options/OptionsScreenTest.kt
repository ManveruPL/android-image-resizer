package pl.fehu.imageresizer.ui.options

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.ui.home.JobDraftViewModel
import pl.fehu.imageresizer.ui.theme.BatchResizerTheme
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

/** Covers application-plan.md M5's AC for Options (§9.2): invalid inputs show errors, PNG disabled for Max file size. */
@RunWith(AndroidJUnit4::class)
class OptionsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newViewModel(): JobDraftViewModel {
        val uriPermissionManager = UriPermissionManager(context.contentResolver)
        val jobStore = JobStore(File(context.cacheDir, "options_test_jobs_${System.nanoTime()}"))
        val dataStore = newTestPreferencesDataStore(context)
        return JobDraftViewModel(
            savedStateHandle = SavedStateHandle(),
            inputResolver = InputResolver(context.contentResolver),
            uriPermissionManager = uriPermissionManager,
            jobScheduler = JobScheduler(context, jobStore, uriPermissionManager),
            decoder = Decoder(context.contentResolver),
            settingsRepository = SettingsRepository(dataStore),
            presetRepository = PresetRepository(dataStore),
        )
    }

    @Test
    fun outOfRangeLongestEdge_showsValidationError() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { OptionsScreen(viewModel, onBack = {}) } }

        viewModel.updateConfig { it.copy(resize = ResizeMode.LongestEdge(1600)) }
        // DataStore's own initial load (default config) races with this direct update; the
        // write's resulting emission always arrives last and settles on the correct value, but
        // we must wait for that settling before interacting with the field.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            (viewModel.config.value.resize as? ResizeMode.LongestEdge)?.px == 1600
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1600").performTextReplacement("0")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Must be 1–20000 px").assertIsDisplayed()
    }

    @Test
    fun pngChip_disabledWhenResizeModeIsMaxFileSize() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { OptionsScreen(viewModel, onBack = {}) } }

        viewModel.updateConfig { it.copy(resize = ResizeMode.TargetFileSize(500, null), format = OutputFormat.JPEG) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.config.value.resize is ResizeMode.TargetFileSize }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_FORMAT_PNG_CHIP).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.options_png_disabled_max_file_size)).performScrollTo().assertIsDisplayed()
    }
}
