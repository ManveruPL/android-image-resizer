package pl.fehu.imageresizer.storage

import android.net.Uri
import pl.fehu.imageresizer.domain.model.ConflictPolicy

/**
 * [OutputWriter]'s public surface, extracted so [pl.fehu.imageresizer.work.BatchRunner] can be
 * exercised against a fake in tests (no real SAF tree is available in this test harness; see
 * DECISIONS.md). Same threading contract as [OutputWriter]: call on `Dispatchers.IO`.
 */
interface OutputResolver {
    fun listExistingNames(): MutableSet<String>

    fun resolve(
        desiredName: String,
        mimeType: String,
        policy: ConflictPolicy,
        existingNames: MutableSet<String>,
    ): OutputWriter.Resolution

    fun deletePartial(uri: Uri)
}
