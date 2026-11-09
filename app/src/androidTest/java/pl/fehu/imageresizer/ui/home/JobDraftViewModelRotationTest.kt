package pl.fehu.imageresizer.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.FakeContentProvider
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

/**
 * Covers application-plan.md §9.5 / M7's "rotate screen during selection" matrix row: selected
 * images survive process death via a fresh `JobDraftViewModel` + `SavedStateHandle` restored from
 * the previous one's saved URIs -- exactly what a real rotation-triggered process death looks
 * like (a plain configuration change alone doesn't destroy the ViewModel at all, so it wouldn't
 * actually exercise this path; process death is the real risk `SavedStateHandle` protects against).
 */
@RunWith(AndroidJUnit4::class)
class JobDraftViewModelRotationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newViewModel(savedStateHandle: SavedStateHandle, dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>): JobDraftViewModel {
        val uriPermissionManager = UriPermissionManager(context.contentResolver)
        val jobStore = JobStore(File(context.cacheDir, "rotation_test_jobs_${System.nanoTime()}"))
        return JobDraftViewModel(
            savedStateHandle = savedStateHandle,
            inputResolver = InputResolver(context.contentResolver),
            uriPermissionManager = uriPermissionManager,
            jobScheduler = JobScheduler(context, jobStore, uriPermissionManager),
            decoder = Decoder(context.contentResolver),
            settingsRepository = SettingsRepository(dataStore),
            presetRepository = PresetRepository(dataStore),
        )
    }

    @Test
    fun selectedImages_surviveAFreshSavedStateHandle() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val uri = FakeContentProvider.register(context, "rotate.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        val beforeHandle = SavedStateHandle()
        val before = newViewModel(beforeHandle, dataStore)
        before.addUris(listOf(uri))
        withTimeout(5_000) { while (before.selectedImages.value.isEmpty()) delay(20) }

        // A fresh SavedStateHandle built from the exact Bundle-equivalent state the old one would
        // have saved -- what the system hands a recreated ViewModel after process death.
        val restoredUris = beforeHandle.get<ArrayList<String>>(JobDraftViewModel.KEY_SELECTED_URIS)
        val afterHandle = SavedStateHandle(mapOf(JobDraftViewModel.KEY_SELECTED_URIS to restoredUris))
        val after = newViewModel(afterHandle, dataStore)

        withTimeout(5_000) { while (after.selectedImages.value.isEmpty()) delay(20) }
        assertEquals(before.selectedImages.value.map { it.uri }, after.selectedImages.value.map { it.uri })
    }
}
