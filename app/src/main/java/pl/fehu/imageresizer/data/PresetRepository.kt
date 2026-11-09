package pl.fehu.imageresizer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import pl.fehu.imageresizer.domain.model.Preset
import pl.fehu.imageresizer.ui.options.BuiltInPresets

private val PRESET_LIST_SERIALIZER = ListSerializer(Preset.serializer())

/**
 * User presets, stored as one JSON array (application-plan.md §8). Until the first mutation,
 * [presets] falls back to the five built-ins (seeded "on first launch" without a separate write,
 * since nothing distinguishes a built-in from a user preset once stored -- the user can rename or
 * delete any of them). Reads are main-safe; mutating functions are `suspend`.
 */
class PresetRepository(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val presets: Flow<List<Preset>> = dataStore.data.map { prefs -> currentList(prefs) }

    /** Inserts [preset], or replaces the existing one with the same id. */
    suspend fun save(preset: Preset) = mutate { list ->
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) list.toMutableList().apply { set(index, preset) } else list + preset
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(transform: (List<Preset>) -> List<Preset>) {
        dataStore.edit { prefs ->
            val updated = transform(currentList(prefs))
            prefs[KEY_PRESETS] = json.encodeToString(PRESET_LIST_SERIALIZER, updated)
        }
    }

    private fun currentList(prefs: Preferences): List<Preset> =
        prefs[KEY_PRESETS]?.let { raw -> runCatching { json.decodeFromString(PRESET_LIST_SERIALIZER, raw) }.getOrNull() }
            ?: BuiltInPresets.all

    private companion object {
        val KEY_PRESETS = stringPreferencesKey("presets")
    }
}
