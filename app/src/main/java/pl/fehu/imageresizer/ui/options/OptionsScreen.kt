package pl.fehu.imageresizer.ui.options

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.fehu.imageresizer.R
import pl.fehu.imageresizer.domain.ResizeModeValidation
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.FitMode
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.Preset
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.ui.common.ScreenScaffold
import pl.fehu.imageresizer.ui.home.JobDraftViewModel

@Composable
fun OptionsScreen(viewModel: JobDraftViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val previewName by viewModel.previewName.collectAsStateWithLifecycle()
    val resizeError by viewModel.resizeModeError.collectAsStateWithLifecycle()

    ScreenScaffold(title = stringResource(R.string.screen_options), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(stringResource(R.string.options_section_preset)) {
                PresetSection(viewModel)
            }
            SectionCard(stringResource(R.string.options_section_resize_mode)) {
                ResizeModeSection(config = config, error = resizeError, onChange = viewModel::updateConfig)
            }
            SectionCard(stringResource(R.string.options_section_format)) {
                FormatSection(config = config, onChange = viewModel::updateConfig)
            }
            SectionCard(stringResource(R.string.options_section_names)) {
                NamingSection(config = config, previewName = previewName, onChange = viewModel::updateConfig)
            }
            SectionCard(stringResource(R.string.options_section_metadata)) {
                MetadataSection(config = config, onChange = viewModel::updateConfig)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * A plain list rather than a dropdown: each preset needs its own apply/rename/delete actions,
 * which don't nest cleanly inside a Material3 `DropdownMenuItem` (application-plan.md §9.2, §8).
 */
@Composable
private fun PresetSection(viewModel: JobDraftViewModel) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Preset?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (preset in presets) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.applyPreset(preset) }, modifier = Modifier.weight(1f)) {
                    Text(preset.name, modifier = Modifier.fillMaxWidth())
                }
                IconButton(onClick = { renameTarget = preset }) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.options_rename_preset, preset.name))
                }
                IconButton(onClick = { viewModel.deletePreset(preset.id) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.options_delete_preset, preset.name))
                }
            }
        }
    }
    TextButton(onClick = { showSaveDialog = true }) {
        Text(stringResource(R.string.options_save_as_preset))
    }

    if (showSaveDialog) {
        PresetNameDialog(
            initial = "",
            title = stringResource(R.string.options_save_as_preset),
            onConfirm = { name -> viewModel.saveCurrentAsPreset(name); showSaveDialog = false },
            onDismiss = { showSaveDialog = false },
        )
    }
    renameTarget?.let { preset ->
        PresetNameDialog(
            initial = preset.name,
            title = stringResource(R.string.options_rename_preset_title),
            onConfirm = { name -> viewModel.renamePreset(preset.id, name); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun PresetNameDialog(initial: String, title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.options_preset_name_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ResizeModeSection(config: JobConfig, error: String?, onChange: ((JobConfig) -> JobConfig) -> Unit) {
    val modes = listOf(
        stringResource(R.string.options_resize_original) to ResizeMode.OriginalSize,
        stringResource(R.string.options_resize_longest_edge) to ResizeMode.LongestEdge(1600),
        stringResource(R.string.options_resize_shortest_edge) to ResizeMode.ShortestEdge(1600),
        stringResource(R.string.options_resize_exact_size) to ResizeMode.ExactSize(1600, 1200, FitMode.FIT),
        stringResource(R.string.options_resize_percent) to ResizeMode.Percentage(50),
        stringResource(R.string.options_resize_max_file_size) to ResizeMode.TargetFileSize(500, null),
    )

    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, sample) in modes) {
            FilterChip(
                selected = config.resize::class == sample::class,
                onClick = { onChange { it.copy(resize = sample) } },
                label = { Text(label) },
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.options_allow_upscaling), modifier = Modifier.weight(1f))
        Switch(checked = config.allowUpscale, onCheckedChange = { checked -> onChange { it.copy(allowUpscale = checked) } })
    }

    when (val mode = config.resize) {
        is ResizeMode.OriginalSize -> Unit
        is ResizeMode.LongestEdge -> NumberField(
            label = stringResource(R.string.options_width),
            value = mode.px,
            error = error,
            onChange = { px -> onChange { it.copy(resize = ResizeMode.LongestEdge(px)) } },
        )
        is ResizeMode.ShortestEdge -> NumberField(
            label = stringResource(R.string.options_width),
            value = mode.px,
            error = error,
            onChange = { px -> onChange { it.copy(resize = ResizeMode.ShortestEdge(px)) } },
        )
        is ResizeMode.ExactSize -> {
            NumberField(
                label = stringResource(R.string.options_width),
                value = mode.width,
                error = error,
                onChange = { w -> onChange { it.copy(resize = mode.copy(width = w)) } },
            )
            NumberField(
                label = stringResource(R.string.options_height),
                value = mode.height,
                error = error,
                onChange = { h -> onChange { it.copy(resize = mode.copy(height = h)) } },
            )
            Text(stringResource(R.string.options_fit_mode), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (fit in FitMode.entries) {
                    FilterChip(
                        selected = mode.fit == fit,
                        onClick = { onChange { it.copy(resize = mode.copy(fit = fit)) } },
                        label = { Text(fitModeLabel(fit)) },
                    )
                }
            }
        }
        is ResizeMode.Percentage -> NumberField(
            label = stringResource(R.string.options_percent_label),
            value = mode.percent,
            error = error,
            onChange = { p -> onChange { it.copy(resize = ResizeMode.Percentage(p)) } },
        )
        is ResizeMode.TargetFileSize -> {
            NumberField(
                label = stringResource(R.string.options_max_kb_label),
                value = mode.maxKb,
                error = error,
                onChange = { kb -> onChange { it.copy(resize = mode.copy(maxKb = kb)) } },
            )
            NumberField(
                label = stringResource(R.string.options_max_longest_edge_label),
                value = mode.maxLongestEdgePx ?: 0,
                error = null,
                onChange = { px -> onChange { it.copy(resize = mode.copy(maxLongestEdgePx = px.takeIf { it > 0 })) } },
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, error: String?, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormatSection(config: JobConfig, onChange: ((JobConfig) -> JobConfig) -> Unit) {
    val isMaxFileSize = config.resize is ResizeMode.TargetFileSize
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormatChip(stringResource(R.string.options_format_keep_original), config.format == OutputFormat.KEEP_ORIGINAL) {
            onChange { it.copy(format = OutputFormat.KEEP_ORIGINAL) }
        }
        FormatChip(stringResource(R.string.options_format_jpeg), config.format == OutputFormat.JPEG) {
            onChange { it.copy(format = OutputFormat.JPEG) }
        }
        FormatChip(
            stringResource(R.string.options_format_png),
            config.format == OutputFormat.PNG,
            enabled = !isMaxFileSize,
            modifier = Modifier.testTag(TAG_FORMAT_PNG_CHIP),
        ) { onChange { it.copy(format = OutputFormat.PNG) } }
        FormatChip(stringResource(R.string.options_format_webp), config.format == OutputFormat.WEBP) {
            onChange { it.copy(format = OutputFormat.WEBP) }
        }
    }
    if (isMaxFileSize) {
        Text(
            stringResource(R.string.options_png_disabled_max_file_size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (config.format == OutputFormat.KEEP_ORIGINAL) {
        Text(stringResource(R.string.options_keep_original_note), style = MaterialTheme.typography.bodySmall)
    }
    if (config.format != OutputFormat.PNG) {
        Text(stringResource(R.string.options_quality))
        Slider(
            value = config.quality.toFloat(),
            onValueChange = { q -> onChange { it.copy(quality = q.toInt()) } },
            valueRange = 1f..100f,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.options_convert_srgb), modifier = Modifier.weight(1f))
        Switch(
            checked = config.color == ColorPolicy.CONVERT_TO_SRGB,
            onCheckedChange = { checked -> onChange { it.copy(color = if (checked) ColorPolicy.CONVERT_TO_SRGB else ColorPolicy.KEEP_SOURCE) } },
        )
    }
    Text(stringResource(R.string.options_only_process))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        val otherFormats = setOf(InputFormat.GIF, InputFormat.BMP, InputFormat.DNG, InputFormat.OTHER)
        val chipDefs = listOf(
            "JPEG" to setOf(InputFormat.JPEG),
            "PNG" to setOf(InputFormat.PNG),
            "WebP" to setOf(InputFormat.WEBP),
            "HEIC" to setOf(InputFormat.HEIF),
            "AVIF" to setOf(InputFormat.AVIF),
            "Other" to otherFormats,
        )
        for ((label, formats) in chipDefs) {
            val selected = config.inputFilter.isNotEmpty() && formats.all { it in config.inputFilter }
            FilterChip(
                selected = selected,
                onClick = {
                    onChange { current ->
                        val newFilter = if (selected) current.inputFilter - formats else current.inputFilter + formats
                        current.copy(inputFilter = newFilter)
                    }
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, enabled = enabled, modifier = modifier)
}

/** Test tag disambiguating the Format section's PNG chip from the "Only process" filter's PNG chip. */
const val TAG_FORMAT_PNG_CHIP = "format_png_chip"

@Composable
private fun NamingSection(config: JobConfig, previewName: String?, onChange: ((JobConfig) -> JobConfig) -> Unit) {
    var pattern by remember(config.naming.pattern) { mutableStateOf(config.naming.pattern) }
    OutlinedTextField(
        value = pattern,
        onValueChange = { newPattern ->
            pattern = newPattern
            onChange { it.copy(naming = it.naming.copy(pattern = newPattern)) }
        },
        label = { Text(stringResource(R.string.options_pattern_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for (token in listOf("{name}", "{w}", "{h}", "{index}", "{date}")) {
            TextButton(onClick = {
                pattern += token
                onChange { it.copy(naming = it.naming.copy(pattern = pattern)) }
            }) { Text(token) }
        }
    }
    Text(stringResource(R.string.options_preview_label, previewName ?: "—"), style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()
    Text(stringResource(R.string.options_conflict_label))
    for (policy in ConflictPolicy.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = config.conflict == policy, onClick = { onChange { it.copy(conflict = policy) } })
            Text(conflictLabel(policy))
        }
    }
}

@Composable
private fun fitModeLabel(fit: FitMode): String = when (fit) {
    FitMode.FIT -> stringResource(R.string.options_fit_fit)
    FitMode.FILL -> stringResource(R.string.options_fit_fill)
    FitMode.STRETCH -> stringResource(R.string.options_fit_stretch)
}

@Composable
private fun conflictLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.RENAME -> stringResource(R.string.options_conflict_rename)
    ConflictPolicy.SKIP -> stringResource(R.string.options_conflict_skip)
    ConflictPolicy.OVERWRITE -> stringResource(R.string.options_conflict_overwrite)
}

@Composable
private fun MetadataSection(config: JobConfig, onChange: ((JobConfig) -> JobConfig) -> Unit) {
    for (policy in MetadataPolicy.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = config.metadata == policy, onClick = { onChange { it.copy(metadata = policy) } })
            Text(metadataLabel(policy))
        }
    }
}

@Composable
private fun metadataLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.KEEP_ALL -> stringResource(R.string.options_metadata_keep_all)
    MetadataPolicy.STRIP_GPS -> stringResource(R.string.options_metadata_strip_gps)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.options_metadata_strip_all)
}
