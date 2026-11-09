package pl.fehu.imageresizer

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.engine.FormatSupport
import pl.fehu.imageresizer.engine.ImageProcessor
import pl.fehu.imageresizer.engine.RealImageProcessor
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Manual dependency container (no DI framework). Holds app-scoped singletons;
 * repositories and the job scheduler are added here as milestones land.
 *
 * Created once in [App.onCreate]; safe to read from any thread after that.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val uriPermissionManager = UriPermissionManager(appContext.contentResolver)
    val inputResolver = InputResolver(appContext.contentResolver)
    val jobStore = JobStore(appContext.filesDir)
    val imageProcessor: ImageProcessor = RealImageProcessor(appContext.contentResolver)

    /** Probes real HEIF/AVIF decode support once; cheap after the first call. */
    val formatSupport: FormatSupport by lazy { FormatSupport.probe(appContext) }

    val jobScheduler = JobScheduler(appContext, jobStore, uriPermissionManager)

    val settingsRepository = SettingsRepository(appContext.settingsDataStore)
    val presetRepository = PresetRepository(appContext.settingsDataStore)
}
