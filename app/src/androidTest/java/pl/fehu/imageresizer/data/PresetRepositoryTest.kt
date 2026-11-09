package pl.fehu.imageresizer.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.Preset
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.newTestPreferencesDataStore
import pl.fehu.imageresizer.ui.options.BuiltInPresets

/** Covers application-plan.md M6's AC: presets create/rename/delete, seeded from the built-ins on first use. */
@RunWith(AndroidJUnit4::class)
class PresetRepositoryTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun customPreset(id: String, name: String) = Preset(
        id = id,
        name = name,
        config = JobConfig(
            resize = ResizeMode.Percentage(50),
            format = OutputFormat.JPEG,
            quality = 80,
            naming = NamingSpec(),
            conflict = ConflictPolicy.RENAME,
            metadata = MetadataPolicy.KEEP_ALL,
            color = ColorPolicy.CONVERT_TO_SRGB,
            outputTreeUri = "",
        ),
    )

    @Test
    fun presets_defaultToBuiltInsBeforeAnyMutation() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        assertEquals(BuiltInPresets.all, PresetRepository(dataStore).presets.first())
    }

    @Test
    fun save_addsNewPreset_andSurvivesAFreshInstance() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        PresetRepository(dataStore).save(customPreset("custom-1", "My preset"))

        val reloaded = PresetRepository(dataStore).presets.first()
        assertEquals(BuiltInPresets.all.size + 1, reloaded.size)
        assertTrue(reloaded.any { it.id == "custom-1" && it.name == "My preset" })
    }

    @Test
    fun save_withExistingId_renamesInPlace_insteadOfDuplicating() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val repo = PresetRepository(dataStore)
        repo.save(customPreset("custom-1", "Original name"))
        repo.save(customPreset("custom-1", "Renamed"))

        val reloaded = repo.presets.first()
        assertEquals(BuiltInPresets.all.size + 1, reloaded.size)
        assertEquals("Renamed", reloaded.single { it.id == "custom-1" }.name)
    }

    @Test
    fun delete_removesAPreset_includingBuiltIns() = runBlocking {
        val dataStore = newTestPreferencesDataStore(context)
        val builtInId = BuiltInPresets.all.first().id
        PresetRepository(dataStore).delete(builtInId)

        val remaining = PresetRepository(dataStore).presets.first()
        assertEquals(BuiltInPresets.all.size - 1, remaining.size)
        assertTrue(remaining.none { it.id == builtInId })
    }
}
