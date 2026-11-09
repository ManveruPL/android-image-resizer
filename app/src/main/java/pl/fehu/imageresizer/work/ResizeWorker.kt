package pl.fehu.imageresizer.work

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import pl.fehu.imageresizer.App
import pl.fehu.imageresizer.domain.model.ItemResult

/**
 * The WorkManager adapter around [BatchRunner]: resolves this process's [App] container (manual
 * DI, no `WorkerFactory`; by the time any work executes, `Application.onCreate()` has always
 * already run), posts the foreground progress notification with a Cancel action, forwards
 * `setProgress`, and persists the final job status. The actual batch logic lives in [BatchRunner]
 * so it can be tested without WorkManager. Runs on the dispatcher WorkManager gives
 * `CoroutineWorker` (not main-safe).
 */
class ResizeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val container get() = (applicationContext as App).container
    private val notifications by lazy { JobNotifications(applicationContext) }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(0, 0, cancelIntent())

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val record = container.jobStore.load(jobId) ?: return Result.failure()

        setForeground(notifications.foregroundInfo(record.results.size, record.inputs.size, cancelIntent()))

        val lowRamDevice = (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val runner = BatchRunner(
            contentResolver = applicationContext.contentResolver,
            jobStore = container.jobStore,
            imageProcessor = container.imageProcessor,
            formatSupport = container.formatSupport,
        )

        try {
            val finalStatus = runner.run(
                record = record,
                lowRamDevice = lowRamDevice,
                isStopped = ::isStopped,
                onProgress = { done, total, lastItemName ->
                    setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_LAST_NAME to lastItemName))
                    setForeground(notifications.foregroundInfo(done, total, cancelIntent()))
                },
            )
            showSummary(jobId, finalStatus)
            return Result.success()
        } catch (e: CancellationException) {
            // Children already finished and persisted their results by the time this throws
            // (see BatchRunner.run's kdoc); the summary must still be shown before this
            // propagates so WorkManager can report the job as cancelled.
            showSummary(jobId, JobStatus.CANCELLED)
            throw e
        }
    }

    private suspend fun showSummary(jobId: String, finalStatus: JobStatus): Unit = withContext(NonCancellable) {
        val finalRecord = container.jobStore.load(jobId) ?: return@withContext
        val succeeded = finalRecord.results.count { it is ItemResult.Success }
        val failed = finalRecord.results.count { it is ItemResult.Failed }
        val skipped = finalRecord.results.count { it is ItemResult.Skipped }
        notifications.showSummary(succeeded, failed, skipped, cancelled = finalStatus == JobStatus.CANCELLED)
    }

    private fun cancelIntent() = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_LAST_NAME = "lastItemName"
    }
}
