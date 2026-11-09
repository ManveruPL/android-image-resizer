package pl.fehu.imageresizer.work

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pl.fehu.imageresizer.domain.DimensionCalculator
import pl.fehu.imageresizer.domain.FileNamer
import pl.fehu.imageresizer.domain.FormatResolver
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.engine.Decoder
import pl.fehu.imageresizer.engine.FormatSupport
import pl.fehu.imageresizer.engine.ImageProcessor
import pl.fehu.imageresizer.engine.MetadataCopier
import pl.fehu.imageresizer.storage.OutputResolver
import pl.fehu.imageresizer.storage.OutputWriter

/**
 * Orchestrates one batch job: per-item format filtering, naming + conflict resolution, decode/
 * encode via [imageProcessor], metadata copy, `OutOfMemoryError` retry-at-parallelism-1, and
 * result persistence via [jobStore] (application-plan.md §7.2). Deliberately independent of
 * `CoroutineWorker`/WorkManager so it can be driven by a fake [ImageProcessor] and [OutputResolver]
 * in tests; [ResizeWorker] is the thin WorkManager adapter around it. Call [run] on
 * `Dispatchers.Default`; not main-safe.
 */
class BatchRunner(
    private val contentResolver: ContentResolver,
    private val jobStore: JobStore,
    private val imageProcessor: ImageProcessor,
    private val formatSupport: FormatSupport,
    private val outputResolverFactory: (Uri) -> OutputResolver = { OutputWriter(contentResolver, it) },
) {
    private val decoder = Decoder(contentResolver)
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    /**
     * Runs every input in [record] that doesn't already have a result (so a resumed job skips
     * what a previous, interrupted run already finished), honoring [isStopped] between items, and
     * reports progress via [onProgress] after each one. Returns the job's final status.
     */
    suspend fun run(
        record: JobRecord,
        lowRamDevice: Boolean,
        isStopped: () -> Boolean,
        onProgress: suspend (done: Int, total: Int, lastItemName: String) -> Unit,
    ): JobStatus {
        val jobId = record.jobId
        val config = record.config
        val total = record.inputs.size
        val alreadyDone = record.results.mapTo(HashSet()) { it.input.uri }
        val pending = record.inputs.filter { it.uri !in alreadyDone }

        val outputResolver = try {
            outputResolverFactory(config.outputTreeUri.toUri())
        } catch (e: Exception) {
            return failAllPending(jobId, pending, total, record.results.size, "output folder is not accessible: ${e.message}", onProgress)
        }
        val existingNames = try {
            outputResolver.listExistingNames()
        } catch (e: Exception) {
            return failAllPending(jobId, pending, total, record.results.size, "output folder is not accessible: ${e.message}", onProgress)
        }

        val indexByUri = record.inputs.withIndex().associate { (i, input) -> input.uri to i + 1 }
        val dateToken = dateFormat.format(Date(record.createdAtMillis))
        val nameMutex = Mutex()
        val parallelism = if (lowRamDevice) 1 else 2
        val semaphore = Semaphore(parallelism)
        val doneCount = AtomicInteger(record.results.size)
        val wasStopped = AtomicBoolean(false)

        val finalStatus: JobStatus
        try {
            coroutineScope {
                val jobs = pending.map { input ->
                    async(Dispatchers.Default) {
                        if (isStopped()) {
                            wasStopped.set(true)
                            return@async
                        }
                        semaphore.withPermit {
                            if (isStopped()) {
                                wasStopped.set(true)
                                return@withPermit
                            }
                            val result = processItem(
                                input = input,
                                config = config,
                                outputResolver = outputResolver,
                                existingNames = existingNames,
                                nameMutex = nameMutex,
                                lowRamDevice = lowRamDevice,
                                parallelism = parallelism,
                                semaphore = semaphore,
                                index = indexByUri.getValue(input.uri),
                                batchSize = total,
                                dateToken = dateToken,
                            )
                            // Not cancellable: the output file (if any) is already on disk by now,
                            // so the record must reflect it even if this coroutine is being stopped.
                            withContext(NonCancellable) {
                                jobStore.appendResult(jobId, result)
                                onProgress(doneCount.incrementAndGet(), total, input.displayName)
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
            finalStatus = if (wasStopped.get() || isStopped()) JobStatus.CANCELLED else JobStatus.SUCCEEDED
        } catch (e: CancellationException) {
            // A cancelled Deferred throws from awaitAll() even once its body already ran to
            // completion, so this still fires after every in-flight item finished and persisted
            // its own result above; the job's terminal status must be saved before rethrowing.
            withContext(NonCancellable) { jobStore.updateStatus(jobId, JobStatus.CANCELLED) }
            throw e
        }

        jobStore.updateStatus(jobId, finalStatus)
        return finalStatus
    }

    private suspend fun failAllPending(
        jobId: String,
        pending: List<InputImage>,
        total: Int,
        alreadyDone: Int,
        reason: String,
        onProgress: suspend (done: Int, total: Int, lastItemName: String) -> Unit,
    ): JobStatus {
        var done = alreadyDone
        for (input in pending) {
            jobStore.appendResult(jobId, ItemResult.Failed(input, reason))
            done++
            onProgress(done, total, input.displayName)
        }
        jobStore.updateStatus(jobId, JobStatus.FAILED)
        return JobStatus.FAILED
    }

    private suspend fun processItem(
        input: InputImage,
        config: JobConfig,
        outputResolver: OutputResolver,
        existingNames: MutableSet<String>,
        nameMutex: Mutex,
        lowRamDevice: Boolean,
        parallelism: Int,
        semaphore: Semaphore,
        index: Int,
        batchSize: Int,
        dateToken: String,
    ): ItemResult {
        if (config.inputFilter.isNotEmpty() && input.format !in config.inputFilter) {
            return ItemResult.Skipped(input, "excluded by the selected input formats")
        }
        if (!formatSupport.isSupported(input.format)) {
            return ItemResult.Skipped(input, "not supported on this device")
        }

        val inputUri = input.uri.toUri()
        val sourceDims = try {
            decoder.readDimensions(inputUri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return ItemResult.Failed(input, "could not read image: ${t.message}")
        }

        val targetDims = DimensionCalculator.compute(sourceDims.first, sourceDims.second, config.resize, config.allowUpscale)
        val resolvedFormat = FormatResolver.resolve(input.format, config.format)
        val baseName = FileNamer.buildBaseName(
            pattern = config.naming.pattern,
            originalName = input.displayName,
            width = targetDims.finalWidth,
            height = targetDims.finalHeight,
            index = index,
            batchSize = batchSize,
            date = dateToken,
            presetName = null,
        )
        val desiredName = FileNamer.withExtension(baseName, resolvedFormat)
        val mimeType = FormatResolver.mimeTypeFor(resolvedFormat)

        val resolution = nameMutex.withLock {
            outputResolver.resolve(desiredName, mimeType, config.conflict, existingNames)
        }
        val ready = when (resolution) {
            is OutputWriter.Resolution.Skip -> return ItemResult.Skipped(input, "a file with this name already exists")
            is OutputWriter.Resolution.Ready -> resolution
        }

        return try {
            val processed = processWithOomRetry(inputUri, ready.uri, input, config, lowRamDevice, semaphore, parallelism)
            copyMetadata(inputUri, ready.uri, config.metadata, processed.outWidth, processed.outHeight)
            ItemResult.Success(
                input = input,
                outputName = ready.displayName,
                outWidth = processed.outWidth,
                outHeight = processed.outHeight,
                bytesBefore = input.sizeBytes,
                bytesAfter = processed.bytesWritten,
                note = processed.note,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            outputResolver.deletePartial(ready.uri)
            ItemResult.Failed(input, t.message ?: t::class.simpleName.orEmpty())
        }
    }

    /** `OutOfMemoryError` per item: `System.gc()`, retry once with the rest of the batch paused. */
    private suspend fun processWithOomRetry(
        inputUri: Uri,
        outputUri: Uri,
        input: InputImage,
        config: JobConfig,
        lowRamDevice: Boolean,
        semaphore: Semaphore,
        parallelism: Int,
    ) = try {
        imageProcessor.process(inputUri, outputUri, input.format, config, lowRamDevice)
    } catch (oom: OutOfMemoryError) {
        val extraPermits = parallelism - 1
        if (extraPermits > 0) repeat(extraPermits) { semaphore.acquire() }
        try {
            System.gc()
            // Reuses the caller's lowRamDevice as-is (application-plan.md §7.2 only calls for
            // parallelism 1 on retry, via the extra permits above, not a different decode policy).
            imageProcessor.process(inputUri, outputUri, input.format, config, lowRamDevice)
        } finally {
            if (extraPermits > 0) repeat(extraPermits) { semaphore.release() }
        }
    }

    /** Metadata is best-effort (§5.4): a copy failure must never fail an otherwise-successful item. */
    private fun copyMetadata(inputUri: Uri, outputUri: Uri, policy: MetadataPolicy, width: Int, height: Int) {
        try {
            val sourceExif = try {
                contentResolver.openInputStream(inputUri)?.use { ExifInterface(it) }
            } catch (e: Exception) {
                null
            }
            contentResolver.openFileDescriptor(outputUri, "rw")?.use { pfd ->
                val outputExif = ExifInterface(pfd.fileDescriptor)
                MetadataCopier.copy(sourceExif, outputExif, policy, width, height)
            }
        } catch (e: Exception) {
            // Best-effort; see MetadataCopier's kdoc.
        }
    }
}
