package pl.fehu.imageresizer.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.FakeContentProvider
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.ui.theme.BatchResizerTheme
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

/**
 * Covers application-plan.md M5's AC for Home (§9.1): Start disabled without folder/images, and
 * the "Convert HEIC to JPEG" hint chip. Images come from [FakeContentProvider] (real, queryable
 * `content://` URIs) fed directly into the view model -- the system photo/file/folder pickers
 * themselves can't be driven without a UI-automator dependency (see DECISIONS.md).
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newViewModel(): JobDraftViewModel {
        val uriPermissionManager = UriPermissionManager(context.contentResolver)
        val jobStore = JobStore(File(context.cacheDir, "home_test_jobs_${System.nanoTime()}"))
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

    private fun registerJpeg(name: String = "photo.jpg") =
        FakeContentProvider.register(context, name, "image/jpeg", byteArrayOf(1, 2, 3))

    private fun registerHeic(name: String = "photo.heic") =
        FakeContentProvider.register(context, name, "application/octet-stream", byteArrayOf(1, 2, 3))

    @Test
    fun startButton_disabledWithoutImagesOrOutputFolder() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        composeRule.onNodeWithText(context.getString(R.string.home_start_button, 0)).assertIsNotEnabled()
    }

    @Test
    fun startButton_enabledOnceImagesAndOutputFolderAreSet() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        val uri = registerJpeg()
        composeRule.runOnUiThread { viewModel.addUris(listOf(uri)) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedImages.value.isNotEmpty() }
        val outputUri = FakeContentProvider.treeUriFor(context.cacheDir)
        composeRule.runOnUiThread { viewModel.setOutputFolder(outputUri, "cache") }
        // SettingsRepository's own initial (empty) load races with this direct set; its write's
        // resulting emission always settles last, but we must wait for that before asserting.
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.outputTreeUri.value == outputUri }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.home_start_button, 1)).assertIsEnabled()
    }

    @Test
    fun heicHintChip_appearsOnlyWhenHeicIsSelected() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        val heicUri = registerHeic()
        composeRule.runOnUiThread { viewModel.addUris(listOf(heicUri)) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedImages.value.isNotEmpty() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.home_heic_hint, 1)).assertIsDisplayed()
    }

    @Test
    fun overwriteWarning_shownWhenOutputFolderEqualsSourceFolder() {
        val testFolder = File(context.cacheDir, "overwrite_test_${System.nanoTime()}").apply { mkdirs() }
        File(testFolder, "a.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val folderUri = FakeContentProvider.treeUriFor(testFolder)

        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        composeRule.runOnUiThread {
            viewModel.addFolder(folderUri, recursive = false)
            viewModel.updateConfig { it.copy(conflict = ConflictPolicy.OVERWRITE) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.selectedImages.value.isNotEmpty() && viewModel.config.value.conflict == ConflictPolicy.OVERWRITE
        }
        composeRule.runOnUiThread { viewModel.setOutputFolder(folderUri, "overwrite_test") }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.outputTreeUri.value == folderUri }
        composeRule.waitForIdle()

        val startLabel = context.getString(R.string.home_start_button, viewModel.selectedImages.value.size)
        composeRule.onNodeWithText(startLabel).performClick()

        composeRule.onNodeWithText(context.getString(R.string.home_overwrite_warning_title)).assertIsDisplayed()
    }

    @Test
    fun handleShareIntent_actionSend_addsTheSharedImage() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        val uri = registerJpeg("shared.jpg")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        composeRule.runOnUiThread { viewModel.handleShareIntent(intent) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedImages.value.isNotEmpty() }

        assertEquals(listOf(uri.toString()), viewModel.selectedImages.value.map { it.uri })
    }

    @Test
    fun handleShareIntent_actionSendMultiple_addsAllSharedImages() {
        val viewModel = newViewModel()
        composeRule.setContent { BatchResizerTheme { HomeScreen(viewModel, onOpenOptions = {}, onStarted = {}) } }

        val uris = arrayListOf(registerJpeg("shared1.jpg"), registerJpeg("shared2.jpg"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        composeRule.runOnUiThread { viewModel.handleShareIntent(intent) }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedImages.value.size == 2 }

        assertEquals(uris.map { it.toString() }.toSet(), viewModel.selectedImages.value.map { it.uri }.toSet())
    }
}
