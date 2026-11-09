package pl.fehu.imageresizer.ui.home

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.fehu.imageresizer.AppContainer
import pl.fehu.imageresizer.data.PresetRepository
import pl.fehu.imageresizer.data.SettingsRepository
import pl.fehu.imageresizer.domain.DimensionCalculator
import pl.fehu.imageresizer.domain.FileNamer
import pl.fehu.imageresizer.domain.FormatResolver
import pl.fehu.imageresizer.domain.ResizeModeValidation
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.Preset
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.storage.InputResolver
import pl.fehu.imageresizer.storage.UriPermissionManager
import pl.fehu.imageresizer.ui.options.BuiltInPresets
import pl.fehu.imageresizer.work.JobScheduler

/**
 * Shared draft state for the Home and Options screens: selected images, output folder and the
 * [JobConfig] being edited (application-plan.md §9.1, §9.2). Selected URIs survive process death
 * via [SavedStateHandle] only ([InputImage] details are re-resolved from them on restore, per
 * spec §9.5) -- they're per-job, not meant to outlive a cold restart. The config, output folder
 * and "include subfolders" flag are durable: [settingsRepository] (DataStore) is the source of
 * truth, loaded on init and written through on every change, so they survive a cold app restart
 * too (application-plan.md §8, M6's AC).
 */
class JobDraftViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val inputResolver: InputResolver,
    private val uriPermissionManager: UriPermissionManager,
    private val jobScheduler: JobScheduler,
    private val decoder: Decoder,
    private val settingsRepository: SettingsRepository,
    private val presetRepository: PresetRepository,
) : ViewModel() {

    private val _selectedImages = MutableStateFlow<List<InputImage>>(emptyList())
    val selectedImages: StateFlow<List<InputImage>> = _selectedImages.asStateFlow()

    private val _sourceFolderUri = MutableStateFlow<Uri?>(savedStateHandle.get<String>(KEY_SOURCE_FOLDER)?.toUri())
    val sourceFolderUri: StateFlow<Uri?> = _sourceFolderUri.asStateFlow()

    private val _outputTreeUri = MutableStateFlow<Uri?>(null)
    val outputTreeUri: StateFlow<Uri?> = _outputTreeUri.asStateFlow()

    private val _outputDisplayName = MutableStateFlow<String?>(null)
    val outputDisplayName: StateFlow<String?> = _outputDisplayName.asStateFlow()

    private val _config = MutableStateFlow(defaultConfig())
    val config: StateFlow<JobConfig> = _config.asStateFlow()

    private val _recursiveFolders = MutableStateFlow(false)
    val recursiveFolders: StateFlow<Boolean> = _recursiveFolders.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    /** A suggested output file name for the first selected image, or null until it resolves. */
    val previewName: StateFlow<String?> = combine(selectedImages, config) { images, cfg -> images.firstOrNull() to cfg }
        .map { (first, cfg) -> first?.let { buildPreviewName(it, cfg) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val formatCounts: StateFlow<Map<InputFormat, Int>> = selectedImages
        .map { images -> images.groupingBy(InputImage::format).eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val resizeModeError: StateFlow<String?> = config
        .map { ResizeModeValidation.errorFor(it.resize, it.allowUpscale) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        val savedUris = savedStateHandle.get<ArrayList<String>>(KEY_SELECTED_URIS)
        if (!savedUris.isNullOrEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                _selectedImages.value = inputResolver.resolveUris(savedUris.map { it.toUri() })
            }
        }
        viewModelScope.launch { settingsRepository.jobConfig.collect { _config.value = it ?: defaultConfig() } }
        viewModelScope.launch { settingsRepository.outputTreeUri.collect { _outputTreeUri.value = it?.toUri() } }
        viewModelScope.launch { settingsRepository.outputTreeDisplayName.collect { _outputDisplayName.value = it } }
        viewModelScope.launch { settingsRepository.includeSubfolders.collect { _recursiveFolders.value = it } }
        viewModelScope.launch { presetRepository.presets.collect { _presets.value = it } }
    }

    /** Handles ACTION_SEND / ACTION_SEND_MULTIPLE (share-intent URIs aren't persistable). */
    fun handleShareIntent(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableUriCompat()?.let(::listOf).orEmpty()
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableUriListCompat().orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addUris(uris)
    }

    fun addUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = inputResolver.resolveUris(uris)
            mergeImages(resolved)
        }
    }

    fun addFolder(treeUri: Uri, recursive: Boolean) {
        _recursiveFolders.value = recursive
        viewModelScope.launch { settingsRepository.saveIncludeSubfolders(recursive) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uriPermissionManager.takeReadPermission(treeUri)
            } catch (e: SecurityException) {
                // Not persistable; folder enumeration below may still work for this session.
            }
            val resolved = inputResolver.resolveFolder(treeUri, recursive)
            _sourceFolderUri.value = treeUri
            savedStateHandle[KEY_SOURCE_FOLDER] = treeUri.toString()
            mergeImages(resolved)
        }
    }

    fun setIncludeSubfolders(value: Boolean) {
        _recursiveFolders.value = value
        viewModelScope.launch { settingsRepository.saveIncludeSubfolders(value) }
    }

    fun removeImage(uri: String) {
        _selectedImages.value = _selectedImages.value.filterNot { it.uri == uri }
        persistSelectedUris()
    }

    fun clearAll() {
        _selectedImages.value = emptyList()
        _sourceFolderUri.value = null
        savedStateHandle[KEY_SOURCE_FOLDER] = null
        persistSelectedUris()
    }

    fun setOutputFolder(treeUri: Uri, displayName: String?) {
        try {
            uriPermissionManager.takeReadWritePermission(treeUri)
        } catch (e: SecurityException) {
            // Best-effort; OutputWriter will fail per item at run time if it truly didn't stick.
        }
        _outputTreeUri.value = treeUri
        _outputDisplayName.value = displayName
        viewModelScope.launch { settingsRepository.saveOutputFolder(treeUri.toString(), displayName) }
    }

    fun updateConfig(transform: (JobConfig) -> JobConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        viewModelScope.launch { settingsRepository.saveJobConfig(updated) }
    }

    fun applyPreset(preset: Preset) {
        updateConfig { current -> preset.config.copy(outputTreeUri = current.outputTreeUri) }
    }

    /** Home's "Convert HEIC to JPEG" quick action (application-plan.md §9.1). */
    fun applyQuickHeicToJpeg() = applyPreset(BuiltInPresets.heicToJpeg)

    /** Saves the current (in-progress) config as a new named preset; never includes the output folder. */
    fun saveCurrentAsPreset(name: String) {
        val preset = Preset(id = java.util.UUID.randomUUID().toString(), name = name, config = _config.value.copy(outputTreeUri = ""))
        viewModelScope.launch { presetRepository.save(preset) }
    }

    fun renamePreset(id: String, newName: String) {
        viewModelScope.launch {
            val current = presets.value.find { it.id == id } ?: return@launch
            presetRepository.save(current.copy(name = newName))
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch { presetRepository.delete(id) }
    }

    suspend fun startJob(): String? {
        val images = _selectedImages.value
        val outputUri = _outputTreeUri.value ?: return null
        if (images.isEmpty()) return null
        _isStarting.value = true
        return try {
            val configWithOutput = _config.value.copy(outputTreeUri = outputUri.toString())
            val jobId = jobScheduler.start(configWithOutput, images)
            _selectedImages.value = emptyList()
            _sourceFolderUri.value = null
            persistSelectedUris()
            jobId
        } finally {
            _isStarting.value = false
        }
    }

    private fun mergeImages(resolved: List<InputImage>) {
        val existing = _selectedImages.value.associateBy { it.uri }
        val merged = (existing.values + resolved).distinctBy { it.uri }
        _selectedImages.value = merged
        persistSelectedUris()
    }

    private fun persistSelectedUris() {
        savedStateHandle[KEY_SELECTED_URIS] = ArrayList(_selectedImages.value.map { it.uri })
    }

    private suspend fun buildPreviewName(input: InputImage, config: JobConfig): String? = withContext(Dispatchers.Default) {
        try {
            val uri = input.uri.toUri()
            val (sourceWidth, sourceHeight) = decoder.readDimensions(uri)
            val target = DimensionCalculator.compute(sourceWidth, sourceHeight, config.resize, config.allowUpscale)
            val resolvedFormat = FormatResolver.resolve(input.format, config.format)
            val baseName = FileNamer.buildBaseName(
                pattern = config.naming.pattern,
                originalName = input.displayName,
                width = target.finalWidth,
                height = target.finalHeight,
                index = 1,
                batchSize = _selectedImages.value.size,
                date = PREVIEW_DATE_FORMAT.format(java.util.Date()),
                presetName = null,
            )
            FileNamer.withExtension(baseName, resolvedFormat)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Public so tests can simulate a process-death restore with a fresh `SavedStateHandle`. */
        const val KEY_SELECTED_URIS = "selectedUris"
        private const val KEY_SOURCE_FOLDER = "sourceFolderUri"
        private val PREVIEW_DATE_FORMAT = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)

        /** Pure so the Composable can recompute it from already-collected `State`, not a `.value` read. */
        fun canStart(selectedImages: List<InputImage>, outputTreeUri: Uri?, config: JobConfig): Boolean =
            selectedImages.isNotEmpty() &&
                outputTreeUri != null &&
                ResizeModeValidation.errorFor(config.resize, config.allowUpscale) == null

        /** Hard rule #2: overwrite is only safe with explicit confirmation when output = source folder. */
        fun needsOverwriteConfirmation(config: JobConfig, sourceFolderUri: Uri?, outputTreeUri: Uri?): Boolean =
            config.conflict == ConflictPolicy.OVERWRITE && sourceFolderUri != null && sourceFolderUri == outputTreeUri

        fun defaultConfig(): JobConfig = JobConfig(
            resize = ResizeMode.LongestEdge(1600),
            format = OutputFormat.KEEP_ORIGINAL,
            quality = 85,
            allowUpscale = false,
            naming = NamingSpec(),
            conflict = ConflictPolicy.RENAME,
            metadata = MetadataPolicy.KEEP_ALL,
            inputFilter = emptySet(),
            outputTreeUri = "",
        )

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                JobDraftViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    inputResolver = container.inputResolver,
                    uriPermissionManager = container.uriPermissionManager,
                    jobScheduler = container.jobScheduler,
                    decoder = Decoder(container.appContext.contentResolver),
                    settingsRepository = container.settingsRepository,
                    presetRepository = container.presetRepository,
                )
            }
        }
    }
}

private fun Intent.getParcelableUriCompat(): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

private fun Intent.getParcelableUriListCompat(): List<Uri>? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }

/** Resolves a human-readable name for a SAF tree, falling back to the raw document id. */
fun resolveTreeDisplayName(context: android.content.Context, treeUri: Uri): String? =
    DocumentFile.fromTreeUri(context, treeUri)?.name
