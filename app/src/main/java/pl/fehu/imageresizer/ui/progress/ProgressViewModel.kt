package pl.fehu.imageresizer.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.fehu.imageresizer.AppContainer
import pl.fehu.imageresizer.work.JobProgress
import pl.fehu.imageresizer.work.JobScheduler
import pl.fehu.imageresizer.work.JobState
import pl.fehu.imageresizer.work.JobStore

/** Drives the Progress screen (application-plan.md §9.3): live progress, elapsed time/ETA, cancel. */
class ProgressViewModel(
    private val jobScheduler: JobScheduler,
    private val jobStore: JobStore,
) : ViewModel() {

    val progress: StateFlow<JobProgress?> = jobScheduler.progress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    val isFinished: Boolean get() = progress.value?.state?.let { it != JobState.RUNNING } ?: false

    init {
        viewModelScope.launch {
            val startedAt = withContext(Dispatchers.IO) {
                jobScheduler.currentJobId()?.let { jobStore.load(it)?.createdAtMillis }
            }
            while (isActive) {
                if (startedAt != null) _elapsedMillis.value = System.currentTimeMillis() - startedAt
                if (isFinished) break
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun cancel() {
        jobScheduler.cancel()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ProgressViewModel(container.jobScheduler, container.jobStore) }
        }
    }
}
