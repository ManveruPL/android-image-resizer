package pl.fehu.imageresizer.work

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.InputImage
import pl.fehu.imageresizer.domain.model.ItemResult
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.domain.model.MetadataPolicy
import pl.fehu.imageresizer.domain.model.NamingSpec
import pl.fehu.imageresizer.domain.model.OutputFormat
import pl.fehu.imageresizer.domain.model.ResizeMode

class JobStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(keepLast: Int = 10) = JobStore(tempFolder.newFolder(), keepLast)

    private fun config() = JobConfig(
        resize = ResizeMode.OriginalSize,
        format = OutputFormat.JPEG,
        quality = 85,
        naming = NamingSpec(),
        conflict = ConflictPolicy.RENAME,
        metadata = MetadataPolicy.KEEP_ALL,
        outputTreeUri = "content://tree/output",
    )

    private fun input(name: String = "a.jpg") = InputImage("content://img/$name", name, "image/jpeg", InputFormat.JPEG, 100L)

    @Test
    fun create_thenLoad_roundTripsTheRecord() = runTest {
        val store = store()
        val record = store.create("job1", config(), listOf(input()))
        val loaded = store.load("job1")
        assertEquals(record, loaded)
        assertEquals(JobStatus.RUNNING, loaded?.status)
    }

    @Test
    fun load_missingJob_returnsNull() {
        assertNull(store().load("missing"))
    }

    @Test
    fun appendResult_accumulatesAndPersists() = runTest {
        val store = store()
        store.create("job1", config(), listOf(input("a.jpg"), input("b.jpg")))
        store.appendResult("job1", ItemResult.Skipped(input("a.jpg"), "not supported"))
        store.appendResult("job1", ItemResult.Failed(input("b.jpg"), "boom"))
        val loaded = store.load("job1")!!
        assertEquals(2, loaded.results.size)
        assertTrue(loaded.results[0] is ItemResult.Skipped)
        assertTrue(loaded.results[1] is ItemResult.Failed)
    }

    @Test
    fun appendResult_onUnknownJob_isNoOp() = runTest {
        // Must not throw even if the job was pruned or never existed.
        store().appendResult("missing", ItemResult.Failed(input(), "boom"))
    }

    @Test
    fun updateStatus_persists() = runTest {
        val store = store()
        store.create("job1", config(), listOf(input()))
        store.updateStatus("job1", JobStatus.CANCELLED)
        assertEquals(JobStatus.CANCELLED, store.load("job1")?.status)
    }

    @Test
    fun recentJobIds_keepsOnlyTheNewest_andPrunesOlderFiles() = runTest {
        val store = store(keepLast = 3)
        for (i in 1..5) {
            store.create("job$i", config(), listOf(input()))
            // Ensure distinct file mtimes on fast filesystems.
            Thread.sleep(5)
        }
        val ids = store.recentJobIds()
        assertEquals(3, ids.size)
        assertEquals(setOf("job5", "job4", "job3"), ids.toSet())
        assertNull(store.load("job1"))
        assertNull(store.load("job2"))
    }

    @Test
    fun load_corruptFile_returnsNullInsteadOfThrowing() {
        val dir = tempFolder.newFolder()
        val jobsDir = java.io.File(dir, "jobs").apply { mkdirs() }
        java.io.File(jobsDir, "bad.json").writeText("not json")
        assertNull(JobStore(dir).load("bad"))
    }
}
