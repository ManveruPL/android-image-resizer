package pl.fehu.imageresizer.work

import java.util.UUID

enum class JobState { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** A snapshot of [ResizeWorker]'s `WorkInfo`, for the Progress screen (application-plan.md §9.3, §7.3). */
data class JobProgress(
    val workId: UUID,
    val done: Int,
    val total: Int,
    val state: JobState,
    /** Display name of the most recently finished item, or null before the first one finishes. */
    val lastItemName: String? = null,
)
