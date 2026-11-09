package pl.fehu.imageresizer.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.ui.common.ScreenScaffold
import pl.fehu.imageresizer.ui.common.formatDuration
import pl.fehu.imageresizer.work.JobState

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val elapsedMillis by viewModel.elapsedMillis.collectAsStateWithLifecycle()
    var showCancelConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(progress?.state) {
        if (progress?.state != null && progress?.state != JobState.RUNNING) {
            onFinished()
        }
    }

    ScreenScaffold(title = stringResource(R.string.screen_progress), onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = progress
            if (current == null) {
                Text(stringResource(R.string.progress_none_running))
            } else {
                val fraction = if (current.total > 0) current.done.toFloat() / current.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.progress_count, current.done, current.total), style = MaterialTheme.typography.titleMedium)
                current.lastItemName?.let { name ->
                    Text(stringResource(R.string.progress_last_finished, name), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.progress_elapsed, formatDuration(elapsedMillis)), style = MaterialTheme.typography.bodySmall)
                val etaMillis = estimateEtaMillis(elapsedMillis, current.done, current.total)
                if (etaMillis != null) {
                    Text(stringResource(R.string.progress_eta, formatDuration(etaMillis)), style = MaterialTheme.typography.bodySmall)
                }

                if (current.state == JobState.RUNNING) {
                    OutlinedButton(onClick = { showCancelConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.progress_cancel))
                    }
                } else {
                    Text(jobStateLabel(current.state), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.progress_view_results))
                    }
                }
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.progress_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.progress_cancel_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { showCancelConfirm = false; viewModel.cancel() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun jobStateLabel(state: JobState): String = when (state) {
    JobState.RUNNING -> ""
    JobState.SUCCEEDED -> stringResource(R.string.progress_state_succeeded)
    JobState.FAILED -> stringResource(R.string.progress_state_failed)
    JobState.CANCELLED -> stringResource(R.string.progress_state_cancelled)
}

/** Null until at least one item is done (not enough data for an estimate yet). */
fun estimateEtaMillis(elapsedMillis: Long, done: Int, total: Int): Long? {
    if (done <= 0 || total <= done) return null
    val perItem = elapsedMillis.toDouble() / done
    return (perItem * (total - done)).toLong()
}
