package pl.fehu.imageresizer.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.newTestPreferencesDataStore

/**
 * Covers application-plan.md M6's AC that options/folder persist across restarts: each test
 * reads back through a **second** [SettingsRepository] instance over the same DataStore file,
 * which is exactly what a cold app restart looks like (a fresh `AppContainer`/`JobDraftViewModel`
 * reopening the same on-disk store).
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jobConfig_survivesAFreshRepositoryInstance() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val config = JobConfig(
            resize = ResizeMode.LongestEdge(900),
            format = OutputFormat.WEBP,
            quality = 77,
            naming = NamingSpec(pattern = "{name}_{index}"),
            conflict = ConflictPolicy.SKIP,
            metadata = MetadataPolicy.STRIP_ALL,
            color = ColorPolicy.KEEP_SOURCE,
            outputTreeUri = "",
        )
        SettingsRepository(dataStore).saveJobConfig(config)

        assertEquals(config, SettingsRepository(dataStore).jobConfig.first())
    }

    @Test
    fun jobConfig_nullBeforeAnythingIsSaved() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        assertEquals(null, SettingsRepository(dataStore).jobConfig.first())
    }

    @Test
    fun outputFolder_survivesAFreshRepositoryInstance() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        SettingsRepository(dataStore).saveOutputFolder("content://tree/abc", "My folder")

        val reloaded = SettingsRepository(dataStore)
        assertEquals("content://tree/abc", reloaded.outputTreeUri.first())
        assertEquals("My folder", reloaded.outputTreeDisplayName.first())
    }

    @Test
    fun includeSubfolders_defaultsFalse_andSurvivesAFreshInstance() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        assertEquals(false, SettingsRepository(dataStore).includeSubfolders.first())

        SettingsRepository(dataStore).saveIncludeSubfolders(true)
        assertEquals(true, SettingsRepository(dataStore).includeSubfolders.first())
    }

    @Test
    fun notificationPermissionAsked_defaultsFalse_thenLatchesTrue() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        assertEquals(false, SettingsRepository(dataStore).notificationPermissionAsked.first())

        SettingsRepository(dataStore).markNotificationPermissionAsked()
        assertEquals(true, SettingsRepository(dataStore).notificationPermissionAsked.first())
    }
}
