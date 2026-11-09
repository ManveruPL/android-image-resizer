package pl.fehu.imageresizer

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import java.io.File

/** A fresh, isolated DataStore<Preferences> file per call, for tests that need `SettingsRepository`/`PresetRepository`. */
fun newTestPreferencesDataStore(context: Context, name: String = "test_${System.nanoTime()}"): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { File(context.cacheDir, "$name.preferences_pb") })
