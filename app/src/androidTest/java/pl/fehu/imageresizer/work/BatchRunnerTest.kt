package pl.fehu.imageresizer.work

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.TestFixtures
import pl.fehu.imageresizer.domain.model.ColorPolicy
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode
import pl.fehu.imageresizer.engine.FormatSupport
import pl.fehu.imageresizer.engine.ImageProcessor
import pl.fehu.imageresizer.engine.RealImageProcessor

/**
 * Covers application-plan.md M4's AC with a fake [pl.fehu.imageresizer.storage.OutputResolver]
 * (no real SAF tree in this harness, see DECISIONS.md) and fake/real [pl.fehu.imageresizer.engine.ImageProcessor]s:
 * OOM in one item doesn't stop the batch, cancellation stops within one item and a resumed job
 * only reprocesses what's left, plus format-filter/support skips and conflict renaming.
 */
@RunWith(AndroidJUnit4::class)
class BatchRunnerTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var outputDir: File
    private lateinit var jobStore: JobStore
    private lateinit var formatSupport: FormatSupport

    @Before
    fun setUp() {
        outputDir = File(context.cacheDir, "batch_runner_output_${System.nanoTime()}").apply { mkdirs() }
        jobStore = JobStore(File(context.cacheDir, "batch_runner_jobs_${System.nanoTime()}"))
        formatSupport = FormatSupport.probe(context)
    }

    private fun config(
        resize: ResizeMode = ResizeMode.OriginalSize,
        format: OutputFormat = OutputFormat.JPEG,
        conflict: ConflictPolicy = ConflictPolicy.RENAME,
        inputFilter: Set<InputFormat> = emptySet(),
    ): JobConfig = JobConfig(
        resize = resize,
        format = format,
        quality = 90,
        naming = NamingSpec(pattern = "{name}"),
        conflict = conflict,
        metadata = MetadataPolicy.STRIP_ALL,
        color = ColorPolicy.CONVERT_TO_SRGB,
        inputFilter = inputFilter,
        outputTreeUri = "unused-fake-output-resolver",
    )

    private fun image(file: File, format: InputFormat): InputImage =
        InputImage(Uri.fromFile(file).toString(), file.name, "image/jpeg", format, file.length())

    private fun runner(imageProcessor: ImageProcessor = RealImageProcessor(context.contentResolver)) =
        BatchRunner(
            contentResolver = context.contentResolver,
            jobStore = jobStore,
            imageProcessor = imageProcessor,
            formatSupport = formatSupport,
            outputResolverFactory = { FakeOutputResolver(outputDir) },
        )

    @Test
    fun happyPath_allInputsSucceedAndFilesAreWritten() = runBlocking {
        val jpeg = image(TestFixtures.jpegWithExifOrientation6AndGps(context, 400, 300), InputFormat.JPEG)
        val png = image(TestFixtures.pngWithAlpha(context, 200, 200), InputFormat.PNG)
        val record = jobStore.create("happy", config(), listOf(jpeg, png))

        val progressLog = mutableListOf<Pair<Int, Int>>()
        val status = runner().run(record, lowRamDevice = false, isStopped = { false }) { done, total, _ ->
            progressLog += done to total
        }

        assertEquals(JobStatus.SUCCEEDED, status)
        val loaded = jobStore.load("happy")!!
        assertEquals(2, loaded.results.size)
        assertTrue(loaded.results.all { it is ItemResult.Success })
        assertEquals(JobStatus.SUCCEEDED, loaded.status)
        assertEquals(listOf(1 to 2, 2 to 2), progressLog.sortedBy { it.first })
        for (result in loaded.results) {
            val success = result as ItemResult.Success
            assertTrue("expected ${success.outputName} to exist", File(outputDir, success.outputName).length() > 0)
        }
    }

    @Test
    fun inputFilter_excludesNonMatchingFormat() = runBlocking {
        val jpeg = image(TestFixtures.jpegWithExifOrientation6AndGps(context), InputFormat.JPEG)
        val record = jobStore.create("filtered", config(inputFilter = setOf(InputFormat.PNG)), listOf(jpeg))

        runner().run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        val result = jobStore.load("filtered")!!.results.single()
        assertTrue(result is ItemResult.Skipped)
        assertEquals("excluded by the selected input formats", (result as ItemResult.Skipped).reason)
    }

    @Test
    fun unsupportedFormat_isSkipped() = runBlocking {
        // InputFormat.OTHER is never supported (FormatSupportTest.unknownFormat_isNeverSupported).
        val file = TestFixtures.jpegWithExifOrientation6AndGps(context)
        val input = image(file, InputFormat.OTHER)
        val record = jobStore.create("unsupported", config(), listOf(input))

        runner().run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        val result = jobStore.load("unsupported")!!.results.single()
        assertTrue(result is ItemResult.Skipped)
        assertEquals("not supported on this device", (result as ItemResult.Skipped).reason)
    }

    @Test
    fun nameConflict_renamePolicy_appendsNumberedSuffix() = runBlocking {
        // Both inputs collide on their output name because the pattern below ignores {name}.
        val source = TestFixtures.jpegWithExifOrientation6AndGps(context)
        val a = File(context.cacheDir, "same_name_src_a.jpg").apply { source.copyTo(this, overwrite = true) }
        val b = File(context.cacheDir, "same_name_src_b.jpg").apply { source.copyTo(this, overwrite = true) }
        // Force an identical desired base name regardless of source file name.
        val config = config().copy(naming = NamingSpec(pattern = "collide"))
        val record = jobStore.create("conflict", config, listOf(image(a, InputFormat.JPEG), image(b, InputFormat.JPEG)))

        runner().run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        val conflictResults = jobStore.load("conflict")!!.results
        assertTrue("expected all Success, got $conflictResults", conflictResults.all { it is ItemResult.Success })
        val names = conflictResults.map { (it as ItemResult.Success).outputName }.sorted()
        assertEquals(listOf("collide (2).jpg", "collide.jpg"), names)
    }

    @Test
    fun corruptFile_recordsFailed_withoutStoppingOtherItems() = runBlocking {
        val good = image(TestFixtures.jpegWithExifOrientation6AndGps(context), InputFormat.JPEG)
        val corrupt = image(TestFixtures.corruptFile(context), InputFormat.JPEG)
        val record = jobStore.create("corrupt", config(), listOf(good, corrupt))

        val status = runner().run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        assertEquals(JobStatus.SUCCEEDED, status)
        val results = jobStore.load("corrupt")!!.results.associateBy { it.input.uri }
        assertTrue(results.getValue(good.uri) is ItemResult.Success)
        assertTrue(results.getValue(corrupt.uri) is ItemResult.Failed)
    }

    @Test
    fun outOfMemory_retriesOnceThenSucceeds() = runBlocking {
        val file = TestFixtures.jpegWithExifOrientation6AndGps(context)
        val input = image(file, InputFormat.JPEG)
        val record = jobStore.create("oom-retry", config(), listOf(input))
        val flaky = FlakyImageProcessor(RealImageProcessor(context.contentResolver), Uri.fromFile(file), failures = 1)

        runner(flaky).run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        assertEquals(2, flaky.callCountForFailUri.get())
        val result = jobStore.load("oom-retry")!!.results.single()
        assertTrue("expected Success, got $result", result is ItemResult.Success)
    }

    @Test
    fun outOfMemory_persistentlyFails_recordsFailed_doesNotStopBatch() = runBlocking {
        val goodFile = TestFixtures.jpegWithExifOrientation6AndGps(context, 100, 100)
        val oomFile = TestFixtures.pngWithAlpha(context, 100, 100)
        val good = image(goodFile, InputFormat.JPEG)
        val alwaysOom = image(oomFile, InputFormat.PNG)
        val record = jobStore.create("oom-persistent", config(), listOf(good, alwaysOom))
        val flaky = FlakyImageProcessor(RealImageProcessor(context.contentResolver), Uri.fromFile(oomFile), failures = Int.MAX_VALUE)

        val status = runner(flaky).run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        assertEquals(JobStatus.SUCCEEDED, status) // the batch as a whole still finishes
        val results = jobStore.load("oom-persistent")!!.results.associateBy { it.input.uri }
        assertTrue(results.getValue(good.uri) is ItemResult.Success)
        assertTrue(results.getValue(alwaysOom.uri) is ItemResult.Failed)
    }

    @Test
    fun cancellation_stopsAfterCurrentItem_andResumeOnlyProcessesTheRest() = runBlocking {
        // Plain (non-EXIF-rotated) fixtures, each copied to its own distinct path (TestFixtures'
        // generators reuse one fixed filename each): a rotated JPEG decoded under
        // MEMORY_POLICY_LOW_RAM hits a real ImageDecoder bug on the API 28 emulator (see
        // DECISIONS.md), unrelated to what this test is about.
        val firstFile = File(context.cacheDir, "cancel_first.png").also { TestFixtures.pngWithAlpha(context, 100, 100).copyTo(it, overwrite = true) }
        val secondFile = File(context.cacheDir, "cancel_second.webp").also { TestFixtures.webp(context, 100, 100).copyTo(it, overwrite = true) }
        val thirdFile = File(context.cacheDir, "cancel_third.webp").also { TestFixtures.webp(context, 50, 50).copyTo(it, overwrite = true) }
        val first = image(firstFile, InputFormat.PNG)
        val second = image(secondFile, InputFormat.WEBP)
        val third = image(thirdFile, InputFormat.WEBP)
        val record = jobStore.create("cancel", config(), listOf(first, second, third))

        val stopNow = AtomicBoolean(false)
        // lowRamDevice = true forces parallelism 1 so item order (and therefore "stop after the
        // first") is deterministic.
        val status = runner().run(record, lowRamDevice = true, isStopped = { stopNow.get() }) { done, _, _ ->
            if (done == 1) stopNow.set(true)
        }

        assertEquals(JobStatus.CANCELLED, status)
        val afterCancel = jobStore.load("cancel")!!
        assertEquals(1, afterCancel.results.size)
        assertEquals(JobStatus.CANCELLED, afterCancel.status)

        // Resume: a fresh run over the reloaded (partial) record must only process what's left.
        val resumedStatus = runner().run(afterCancel, lowRamDevice = true, isStopped = { false }) { _, _, _ -> }

        assertEquals(JobStatus.SUCCEEDED, resumedStatus)
        val finalRecord = jobStore.load("cancel")!!
        assertEquals(3, finalRecord.results.size)
        assertTrue("expected all Success, got ${finalRecord.results}", finalRecord.results.all { it is ItemResult.Success })
        assertEquals(setOf(first.uri, second.uri, third.uri), finalRecord.results.map { it.input.uri }.toSet())
    }

    @Test
    fun invalidOutputTree_failsEveryPendingItem_withoutCrashing() = runBlocking {
        val input = image(TestFixtures.jpegWithExifOrientation6AndGps(context), InputFormat.JPEG)
        val record = jobStore.create("bad-output", config(), listOf(input))
        val brokenRunner = BatchRunner(
            contentResolver = context.contentResolver,
            jobStore = jobStore,
            imageProcessor = RealImageProcessor(context.contentResolver),
            formatSupport = formatSupport,
            outputResolverFactory = { throw IllegalStateException("tree is gone") },
        )

        val status = brokenRunner.run(record, lowRamDevice = false, isStopped = { false }) { _, _, _ -> }

        assertEquals(JobStatus.FAILED, status)
        val result = jobStore.load("bad-output")!!.results.single()
        assertTrue(result is ItemResult.Failed)
    }
}
