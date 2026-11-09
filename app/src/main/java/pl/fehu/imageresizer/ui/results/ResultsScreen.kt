package pl.fehu.imageresizer.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.ui.common.ScreenScaffold
import pl.fehu.imageresizer.ui.common.formatBytes

@Composable
fun ResultsScreen(
    viewModel: ResultsViewModel,
    onBack: () -> Unit,
    onRetryStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val record by viewModel.record.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filteredResults by viewModel.filteredResults.collectAsStateWithLifecycle()
    val canOpenFolder by viewModel.canOpenFolder.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.screen_results), onBack = onBack) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (record == null) {
                Text(stringResource(R.string.results_empty))
                return@Column
            }

            Text(
                stringResource(R.string.results_totals, totals.succeeded, totals.failed, totals.skipped),
                style = MaterialTheme.typography.titleMedium,
            )
            totals.percentSaved?.let { percent ->
                Text(
                    stringResource(R.string.results_size_saved, formatBytes(totals.bytesBefore), formatBytes(totals.bytesAfter), percent),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == ResultsFilter.ALL, onClick = { viewModel.setFilter(ResultsFilter.ALL) }, label = { Text(stringResource(R.string.results_filter_all)) })
                FilterChip(selected = filter == ResultsFilter.FAILED, onClick = { viewModel.setFilter(ResultsFilter.FAILED) }, label = { Text(stringResource(R.string.results_filter_failed)) })
                FilterChip(selected = filter == ResultsFilter.SKIPPED, onClick = { viewModel.setFilter(ResultsFilter.SKIPPED) }, label = { Text(stringResource(R.string.results_filter_skipped)) })
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(filteredResults, key = { it.input.uri }) { result ->
                    ResultRow(result)
                    HorizontalDivider()
                }
            }

            if (totals.failed > 0) {
                OutlinedButton(onClick = { scope.launch { if (viewModel.retryFailed() != null) onRetryStarted() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.results_retry_failed))
                }
            }
            if (canOpenFolder) {
                Button(
                    onClick = { viewModel.folderIntent()?.let { context.startActivity(it) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.results_open_folder))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: ItemResult) {
    val (icon, subtitle) = when (result) {
        is ItemResult.Success -> R.drawable.ic_check_circle to result.outputName
        is ItemResult.Skipped -> R.drawable.ic_warning to result.reason
        is ItemResult.Failed -> R.drawable.ic_error to result.error
    }
    ListItem(
        headlineContent = { Text(result.input.displayName) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
    )
}
