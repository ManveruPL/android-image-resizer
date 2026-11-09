package pl.fehu.imageresizer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import pl.fehu.imageresizer.domain.model.JobConfig

/**
 * The last used [JobConfig], output folder and a couple of small flags (application-plan.md §8),
 * backed by DataStore Preferences. Reads are main-safe (DataStore does its own I/O dispatching);
 * the `save*` functions are `suspend` and safe to call from any dispatcher.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val jobConfig: Flow<JobConfig?> = dataStore.data.map { prefs ->
        prefs[KEY_JOB_CONFIG]?.let { raw -> runCatching { json.decodeFromString(JobConfig.serializer(), raw) }.getOrNull() }
    }

    val outputTreeUri: Flow<String?> = dataStore.data.map { it[KEY_OUTPUT_URI] }
    val outputTreeDisplayName: Flow<String?> = dataStore.data.map { it[KEY_OUTPUT_NAME] }
    val includeSubfolders: Flow<Boolean> = dataStore.data.map { it[KEY_INCLUDE_SUBFOLDERS] ?: false }
    val notificationPermissionAsked: Flow<Boolean> = dataStore.data.map { it[KEY_NOTIFICATION_ASKED] ?: false }

    suspend fun saveJobConfig(config: JobConfig) {
        dataStore.edit { it[KEY_JOB_CONFIG] = json.encodeToString(JobConfig.serializer(), config) }
    }

    suspend fun saveOutputFolder(treeUri: String, displayName: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_OUTPUT_URI] = treeUri
            if (displayName != null) prefs[KEY_OUTPUT_NAME] = displayName else prefs.remove(KEY_OUTPUT_NAME)
        }
    }

    suspend fun saveIncludeSubfolders(value: Boolean) {
        dataStore.edit { it[KEY_INCLUDE_SUBFOLDERS] = value }
    }

    suspend fun markNotificationPermissionAsked() {
        dataStore.edit { it[KEY_NOTIFICATION_ASKED] = true }
    }

    private companion object {
        val KEY_JOB_CONFIG = stringPreferencesKey("job_config")
        val KEY_OUTPUT_URI = stringPreferencesKey("output_tree_uri")
        val KEY_OUTPUT_NAME = stringPreferencesKey("output_tree_display_name")
        val KEY_INCLUDE_SUBFOLDERS = booleanPreferencesKey("include_subfolders")
        val KEY_NOTIFICATION_ASKED = booleanPreferencesKey("notification_permission_asked")
    }
}
