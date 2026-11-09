package pl.fehu.imageresizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.fehu.imageresizer.ui.navigation.AppNavHost
import pl.fehu.imageresizer.ui.theme.BatchResizerTheme

/**
 * Single activity hosting the Compose navigation graph. Share intents (`ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE`, declared in the manifest) are forwarded to [AppNavHost], which hands
 * them to [pl.fehu.imageresizer.ui.home.JobDraftViewModel] (application-plan.md §9.1).
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way: a denial just means no progress/summary notifications. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            BatchResizerTheme {
                AppNavHost(shareIntent = intent)
            }
        }
    }

    /**
     * Required on API 33+ for [pl.fehu.imageresizer.work.JobNotifications]' progress/summary
     * notifications to show. Asked at most once: `SettingsRepository`'s "asked" flag
     * (application-plan.md §8) stops it from being repeated on every launch after the first,
     * regardless of whether the user granted or denied it.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val settingsRepository = (application as App).container.settingsRepository
        lifecycleScope.launch {
            if (settingsRepository.notificationPermissionAsked.first()) return@launch
            settingsRepository.markNotificationPermissionAsked()
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
