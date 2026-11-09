package pl.fehu.imageresizer.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.ui.common.ScreenScaffold
import pl.fehu.imageresizer.ui.common.Thumbnail
import pl.fehu.imageresizer.ui.common.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: JobDraftViewModel,
    onOpenOptions: () -> Unit,
    onStarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedImages by viewModel.selectedImages.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val outputTreeUri by viewModel.outputTreeUri.collectAsStateWithLifecycle()
    val outputDisplayName by viewModel.outputDisplayName.collectAsStateWithLifecycle()
    val sourceFolderUri by viewModel.sourceFolderUri.collectAsStateWithLifecycle()
    val formatCounts by viewModel.formatCounts.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val includeSubfolders by viewModel.recursiveFolders.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }

    val photosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.addUris(uris)
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addUris(uris)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri, includeSubfolders)
    }
    val outputFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setOutputFolder(uri, resolveTreeDisplayName(context, uri))
    }

    fun launchStart() {
        scope.launch { if (viewModel.startJob() != null) onStarted() }
    }

    ScreenScaffold(title = stringResource(R.string.screen_home)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.applyQuickHeicToJpeg()
                    photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_quick_heic_to_jpeg))
            }

            val heicCount = formatCounts[InputFormat.HEIF] ?: 0
            if (heicCount > 0) {
                AssistChip(onClick = { viewModel.applyQuickHeicToJpeg() }, label = { Text(stringResource(R.string.home_heic_hint, heicCount)) })
            }

            Button(onClick = { showAddSheet = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_add_images))
            }

            if (selectedImages.isEmpty()) {
                Text(stringResource(R.string.home_no_selection), style = MaterialTheme.typography.bodyMedium)
            } else {
                val totalBytes = selectedImages.sumOf { it.sizeBytes ?: 0L }
                Text(
                    stringResource(R.string.home_selection_count, selectedImages.size, formatBytes(totalBytes)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    formatCounts.entries.joinToString(" · ") { (format, count) -> "$count ${format.name}" },
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(selectedImages, key = { it.uri }) { image ->
                        ThumbnailTile(
                            uri = image.uri,
                            displayName = image.displayName,
                            onRemove = { viewModel.removeImage(image.uri) },
                        )
                    }
                }
                TextButton(onClick = viewModel::clearAll) { Text(stringResource(R.string.home_clear_all)) }
            }

            Card(onClick = onOpenOptions, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.home_options_card_title), style = MaterialTheme.typography.titleMedium)
                    Text(configSummary(config), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.home_output_folder_title), style = MaterialTheme.typography.titleMedium)
                        Text(outputDisplayName ?: stringResource(R.string.home_output_folder_unset))
                        if (outputTreeUri == null) {
                            Text(
                                stringResource(R.string.home_output_folder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(onClick = { outputFolderLauncher.launch(null) }) {
                        Text(stringResource(R.string.home_output_folder_change))
                    }
                }
            }

            val canStart = JobDraftViewModel.canStart(selectedImages, outputTreeUri, config)
            Button(
                onClick = {
                    if (JobDraftViewModel.needsOverwriteConfirmation(config, sourceFolderUri, outputTreeUri)) {
                        showOverwriteDialog = true
                    } else {
                        launchStart()
                    }
                },
                enabled = canStart && !isStarting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.home_start_button, selectedImages.size))
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.home_add_images_sheet_title), style = MaterialTheme.typography.titleMedium)
                AddSourceRow(R.drawable.ic_photo, R.string.home_add_photos) {
                    showAddSheet = false
                    photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                AddSourceRow(R.drawable.ic_insert_drive_file, R.string.home_add_files) {
                    showAddSheet = false
                    filesLauncher.launch(arrayOf("image/*"))
                }
                AddSourceRow(R.drawable.ic_folder, R.string.home_add_folder) {
                    showAddSheet = false
                    folderLauncher.launch(null)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.home_include_subfolders), modifier = Modifier.weight(1f))
                    Switch(checked = includeSubfolders, onCheckedChange = viewModel::setIncludeSubfolders)
                }
            }
        }
    }

    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text(stringResource(R.string.home_overwrite_warning_title)) },
            text = { Text(stringResource(R.string.home_overwrite_warning_message)) },
            icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
            confirmButton = {
                TextButton(onClick = { showOverwriteDialog = false; launchStart() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AddSourceRow(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(iconRes), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(labelRes))
        }
    }
}

@Composable
private fun ThumbnailTile(uri: String, displayName: String, onRemove: () -> Unit) {
    Column {
        Box {
            Thumbnail(uri = uri.toUri())
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp).align(Alignment.TopEnd)) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.home_remove_image, displayName),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A short, readable summary of [config] for the Options summary card. */
fun configSummary(config: JobConfig): String {
    val resizePart = when (val mode = config.resize) {
        is ResizeMode.OriginalSize -> "Original size"
        is ResizeMode.LongestEdge -> "Longest edge ${mode.px}px"
        is ResizeMode.ShortestEdge -> "Shortest edge ${mode.px}px"
        is ResizeMode.ExactSize -> "${mode.width}×${mode.height}px"
        is ResizeMode.Percentage -> "${mode.percent}%"
        is ResizeMode.TargetFileSize -> "Max ${mode.maxKb}KB"
    }
    val formatPart = when (config.format) {
        OutputFormat.KEEP_ORIGINAL -> "Keep original"
        OutputFormat.JPEG -> "JPEG ${config.quality}"
        OutputFormat.PNG -> "PNG"
        OutputFormat.WEBP -> "WebP ${config.quality}"
    }
    return "$resizePart · $formatPart"
}
