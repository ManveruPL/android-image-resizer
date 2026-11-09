package pl.fehu.imageresizer.work

import kotlinx.serialization.Serializable
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.domain.model.JobConfig

@Serializable
enum class JobStatus { RUNNING, SUCCEEDED, CANCELLED, FAILED }

/**
 * A batch job's full state, persisted as JSON (application-plan.md §7.1): WorkManager's own
 * `Data` only ever carries the [jobId]. [results] is appended to as items finish, so a job killed
 * mid-run still shows partial results when reloaded.
 */
@Serializable
data class JobRecord(
    val jobId: String,
    val config: JobConfig,
    val inputs: List<InputImage>,
    val createdAtMillis: Long,
    val results: List<ItemResult> = emptyList(),
    val status: JobStatus = JobStatus.RUNNING,
)
