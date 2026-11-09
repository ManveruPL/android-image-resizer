package pl.fehu.imageresizer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.fehu.imageresizer.R

/** Temporary body for screens that are implemented in later milestones (M5). */
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    ScreenScaffold(title = title, onBack = onBack) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.placeholder_coming_soon))
        }
    }
}
