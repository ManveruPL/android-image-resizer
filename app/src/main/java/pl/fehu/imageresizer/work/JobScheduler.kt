package pl.fehu.imageresizer.work

import android.content.Context
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.storage.UriPermissionManager

/**
 * Starts batch jobs as unique work (`ExistingWorkPolicy.KEEP`, application-plan.md §7.3: only one
 * batch runs at a time) and exposes their progress as a [Flow]. Call on `Dispatchers.IO` (writes
 * the job record and takes URI permissions); [progress] is main-safe.
 */
class JobScheduler(
    private val context: Context,
    private val jobStore: JobStore,
    private val uriPermissionManager: UriPermissionManager,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Persists the job, takes the URI permissions it needs (best-effort; a grant that doesn't
     * stick, e.g. a non-persistable share-intent URI, surfaces as a per-item failure instead of
     * blocking the whole batch — see [UriPermissionManager]'s kdoc), and enqueues it.
     */
    suspend fun start(config: JobConfig, inputs: List<InputImage>): String {
        require(inputs.isNotEmpty()) { "cannot start a job with no inputs" }
        val jobId = java.util.UUID.randomUUID().toString()
        takePermissions(config, inputs)
        jobStore.create(jobId, config, inputs)

        val request = OneTimeWorkRequestBuilder<ResizeWorker>()
            .setInputData(workDataOf(ResizeWorker.KEY_JOB_ID to jobId))
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return jobId
    }

    /** Requests cancellation of the current job, if any; a no-op otherwise. */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * The most recently started job's id, or null if none has ever run. Backed by [JobStore]
     * (not an in-memory field) so it survives process death -- the Progress screen needs this
     * when reopened from the notification after the app process was recreated.
     */
    fun currentJobId(): String? = jobStore.recentJobIds(1).firstOrNull()

    /** The current (or most recently finished) job's progress, or null if none has ever run. */
    fun progress(): Flow<JobProgress?> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { infos ->
            infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        }.map { it?.let(::toJobProgress) }

    private fun takePermissions(config: JobConfig, inputs: List<InputImage>) {
        try {
            uriPermissionManager.takeReadWritePermission(config.outputTreeUri.toUri())
        } catch (e: SecurityException) {
            // Already granted, or genuinely inaccessible; OutputWriter will fail per item if so.
        }
        for (input in inputs) {
            try {
                uriPermissionManager.takeReadPermission(input.uri.toUri())
            } catch (e: SecurityException) {
                // Not every input route grants a persistable permission (share intents don't).
            }
        }
    }

    private fun toJobProgress(info: WorkInfo): JobProgress {
        val done = info.progress.getInt(ResizeWorker.KEY_DONE, 0)
        val total = info.progress.getInt(ResizeWorker.KEY_TOTAL, 0)
        val lastItemName = info.progress.getString(ResizeWorker.KEY_LAST_NAME)
        val state = when (info.state) {
            WorkInfo.State.SUCCEEDED -> JobState.SUCCEEDED
            WorkInfo.State.FAILED -> JobState.FAILED
            WorkInfo.State.CANCELLED -> JobState.CANCELLED
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> JobState.RUNNING
        }
        return JobProgress(info.id, done, total, state, lastItemName)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "resize_job"
    }
}
