package pl.fehu.imageresizer.work

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.domain.model.JobConfig

/**
 * Persists [JobRecord]s as one JSON file per job under `filesDir/jobs/{jobId}.json` (application-
 * plan.md §7.1), keeping only the last [keepLast]. Pure JVM I/O, no Android dependency. The
 * mutating methods are `suspend` and serialize with an internal [Mutex] because [ResizeWorker]
 * runs items concurrently (`Semaphore`-limited) and all of them append to the same job's file;
 * call them from any dispatcher. [load] and [recentJobIds] are plain blocking file reads — call
 * on `Dispatchers.IO`.
 */
class JobStore(filesDir: File, private val keepLast: Int = 10) {

    private val jobsDir = File(filesDir, "jobs")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun create(jobId: String, config: JobConfig, inputs: List<InputImage>): JobRecord = mutex.withLock {
        val record = JobRecord(jobId = jobId, config = config, inputs = inputs, createdAtMillis = System.currentTimeMillis())
        save(record)
        prune()
        record
    }

    fun load(jobId: String): JobRecord? {
        val file = fileFor(jobId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(JobRecord.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Appends [result] to the job's result list and persists it. No-op if the job no longer exists. */
    suspend fun appendResult(jobId: String, result: ItemResult): Unit = mutex.withLock {
        val record = load(jobId) ?: return@withLock
        save(record.copy(results = record.results + result))
    }

    suspend fun updateStatus(jobId: String, status: JobStatus): Unit = mutex.withLock {
        val record = load(jobId) ?: return@withLock
        save(record.copy(status = status))
    }

    /** Most recently modified job ids first. */
    fun recentJobIds(limit: Int = keepLast): List<String> =
        jobsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.map { it.nameWithoutExtension }
            .orEmpty()

    private fun save(record: JobRecord) {
        jobsDir.mkdirs()
        val text = json.encodeToString(JobRecord.serializer(), record)
        val tmp = File(jobsDir, "${record.jobId}.json.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(fileFor(record.jobId))) { "Could not persist job ${record.jobId}" }
    }

    private fun prune() {
        val files = jobsDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keepLast).forEach { it.delete() }
    }

    private fun fileFor(jobId: String) = File(jobsDir, "$jobId.json")
}
