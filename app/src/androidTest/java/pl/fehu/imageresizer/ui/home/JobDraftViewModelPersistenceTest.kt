package pl.fehu.imageresizer.ui.home

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.ui.options.BuiltInPresets
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

/**
 * Covers application-plan.md M6's AC: options and folder persist across restarts, presets
 * create/rename/delete/apply, and applying a preset never changes the output folder. A "restart"
 * is a brand-new [JobDraftViewModel] over the *same* DataStore file -- exactly what happens when
 * the app process is recreated and a fresh `AppContainer`/view model reopen the same on-disk store.
 */
@RunWith(AndroidJUnit4::class)
class JobDraftViewModelPersistenceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newViewModel(dataStore: DataStore<Preferences>): JobDraftViewModel {
        val uriPermissionManager = UriPermissionManager(context.contentResolver)
        val jobStore = JobStore(File(context.cacheDir, "persistence_test_jobs_${System.nanoTime()}"))
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
    fun configAndOutputFolder_persistAcrossARestart() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val beforeRestart = newViewModel(dataStore)
        val folderUri = "content://tree/fake-restart".toUri()

        beforeRestart.updateConfig { it.copy(resize = ResizeMode.Percentage(42), format = OutputFormat.WEBP, quality = 55) }
        beforeRestart.setOutputFolder(folderUri, "My Folder")
        withTimeout(5_000) {
            while ((beforeRestart.config.value.resize as? ResizeMode.Percentage)?.percent != 42 || beforeRestart.outputTreeUri.value == null) {
                delay(20)
            }
        }

        val afterRestart = newViewModel(dataStore)
        withTimeout(5_000) {
            while (afterRestart.outputTreeUri.value == null || afterRestart.outputDisplayName.value == null) delay(20)
        }

        assertEquals(ResizeMode.Percentage(42), afterRestart.config.value.resize)
        assertEquals(OutputFormat.WEBP, afterRestart.config.value.format)
        assertEquals(55, afterRestart.config.value.quality)
        assertEquals(folderUri, afterRestart.outputTreeUri.value)
        assertEquals("My Folder", afterRestart.outputDisplayName.value)
    }

    @Test
    fun applyPreset_neverChangesTheOutputFolder() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val viewModel = newViewModel(dataStore)
        val folderUri = "content://tree/fixed-folder".toUri()
        viewModel.setOutputFolder(folderUri, "Fixed Folder")
        withTimeout(5_000) { while (viewModel.outputTreeUri.value == null || viewModel.outputDisplayName.value == null) delay(20) }

        viewModel.applyPreset(BuiltInPresets.web1600)

        assertEquals(ResizeMode.LongestEdge(1600), viewModel.config.value.resize)
        assertEquals(folderUri, viewModel.outputTreeUri.value)
        assertEquals("Fixed Folder", viewModel.outputDisplayName.value)
    }

    @Test
    fun presets_createRenameApplyDelete() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val viewModel = newViewModel(dataStore)

        viewModel.updateConfig { it.copy(resize = ResizeMode.LongestEdge(777), format = OutputFormat.JPEG, quality = 60) }
        withTimeout(5_000) { while ((viewModel.config.value.resize as? ResizeMode.LongestEdge)?.px != 777) delay(20) }

        viewModel.saveCurrentAsPreset("My Custom")
        withTimeout(5_000) { while (viewModel.presets.value.none { it.name == "My Custom" }) delay(20) }
        val created = viewModel.presets.value.first { it.name == "My Custom" }
        assertEquals("", created.config.outputTreeUri)
        assertEquals(ResizeMode.LongestEdge(777), created.config.resize)

        viewModel.renamePreset(created.id, "Renamed Custom")
        withTimeout(5_000) { while (viewModel.presets.value.none { it.name == "Renamed Custom" }) delay(20) }
        assertTrue(viewModel.presets.value.none { it.name == "My Custom" })

        // Diverge, then re-apply the renamed preset to confirm "apply" still works after a rename.
        viewModel.updateConfig { it.copy(resize = ResizeMode.OriginalSize) }
        val renamed = viewModel.presets.value.first { it.name == "Renamed Custom" }
        viewModel.applyPreset(renamed)
        withTimeout(5_000) { while ((viewModel.config.value.resize as? ResizeMode.LongestEdge)?.px != 777) delay(20) }

        viewModel.deletePreset(renamed.id)
        withTimeout(5_000) { while (viewModel.presets.value.any { it.id == renamed.id }) delay(20) }
        assertTrue(viewModel.presets.value.none { it.id == renamed.id })
    }
}
