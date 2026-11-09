package pl.fehu.imageresizer.ui.results

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
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
import pl.fehu.imageresizer.AppContainer
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.work.JobRecord
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobStore

enum class ResultsFilter { ALL, FAILED, SKIPPED }

data class ResultTotals(
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val bytesBefore: Long,
    val bytesAfter: Long,
) {
    /** Null when there's nothing to compare (no successes, or no known original sizes). */
    val percentSaved: Int?
        get() = if (bytesBefore > 0 && succeeded > 0) (100 - (bytesAfter * 100 / bytesBefore)).toInt() else null
}

/** Drives the Results screen (application-plan.md §9.4): totals, filtering, retry, open folder. */
class ResultsViewModel(
    private val jobStore: JobStore,
    private val jobScheduler: JobScheduler,
    private val appContext: Context,
) : ViewModel() {

    private val _record = MutableStateFlow<JobRecord?>(null)
    val record: StateFlow<JobRecord?> = _record.asStateFlow()

    private val _filter = MutableStateFlow(ResultsFilter.ALL)
    val filter: StateFlow<ResultsFilter> = _filter.asStateFlow()

    val filteredResults: StateFlow<List<ItemResult>> = combine(record, filter) { rec, f ->
        val results = rec?.results.orEmpty()
        when (f) {
            ResultsFilter.ALL -> results
            ResultsFilter.FAILED -> results.filterIsInstance<ItemResult.Failed>()
            ResultsFilter.SKIPPED -> results.filterIsInstance<ItemResult.Skipped>()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totals: StateFlow<ResultTotals> = record.map { rec -> totalsOf(rec) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), totalsOf(null))

    val canOpenFolder: StateFlow<Boolean> = record.map { rec -> rec != null && folderIntent(rec) != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _record.value = jobScheduler.currentJobId()?.let { jobStore.load(it) }
        }
    }

    fun setFilter(filter: ResultsFilter) {
        _filter.value = filter
    }

    fun folderIntent(): Intent? = _record.value?.let(::folderIntent)

    private fun folderIntent(rec: JobRecord): Intent? {
        if (rec.config.outputTreeUri.isBlank()) return null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(rec.config.outputTreeUri.toUri(), DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(appContext.packageManager) != null) intent else null
    }

    /** Starts a new job with only the failed items from the current one, same config. Returns the new job id. */
    suspend fun retryFailed(): String? {
        val rec = _record.value ?: return null
        val failedInputs = rec.results.filterIsInstance<ItemResult.Failed>().map { it.input }
        if (failedInputs.isEmpty()) return null
        return jobScheduler.start(rec.config, failedInputs)
    }

    private fun totalsOf(rec: JobRecord?): ResultTotals {
        val results = rec?.results.orEmpty()
        val successes = results.filterIsInstance<ItemResult.Success>()
        return ResultTotals(
            succeeded = successes.size,
            failed = results.count { it is ItemResult.Failed },
            skipped = results.count { it is ItemResult.Skipped },
            bytesBefore = successes.sumOf { it.bytesBefore ?: 0L },
            bytesAfter = successes.sumOf { it.bytesAfter },
        )
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ResultsViewModel(container.jobStore, container.jobScheduler, container.appContext) }
        }
    }
}
