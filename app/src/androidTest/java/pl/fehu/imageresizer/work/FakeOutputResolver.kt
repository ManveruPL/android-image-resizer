package pl.fehu.imageresizer.work

import android.net.Uri
import java.io.File
import pl.fehu.imageresizer.domain.FileNamer
import pl.fehu.imageresizer.domain.model.ConflictPolicy
import pl.fehu.imageresizer.storage.OutputResolver
import pl.fehu.imageresizer.storage.OutputWriter

/**
 * A local-directory-backed [OutputResolver] for [BatchRunnerTest]: no real SAF tree is available
 * in this test harness (see DECISIONS.md), but [BatchRunner] only depends on [OutputResolver]'s
 * interface, so a real local directory stands in for the output tree and its conflict-naming
 * logic mirrors [OutputWriter]'s own (RENAME delegates to the same [FileNamer.resolveConflict]).
 */
class FakeOutputResolver(private val dir: File) : OutputResolver {

    init {
        dir.mkdirs()
    }

    override fun listExistingNames(): MutableSet<String> =
        dir.listFiles()?.mapTo(mutableSetOf()) { it.name } ?: mutableSetOf()

    override fun resolve(
        desiredName: String,
        mimeType: String,
        policy: ConflictPolicy,
        existingNames: MutableSet<String>,
    ): OutputWriter.Resolution {
        if (desiredName !in existingNames) return create(desiredName, existingNames)
        return when (policy) {
            ConflictPolicy.SKIP -> OutputWriter.Resolution.Skip
            ConflictPolicy.RENAME -> create(FileNamer.resolveConflict(desiredName, existingNames), existingNames)
            ConflictPolicy.OVERWRITE -> OutputWriter.Resolution.Ready(Uri.fromFile(File(dir, desiredName)), desiredName)
        }
    }

    override fun deletePartial(uri: Uri) {
        uri.path?.let { File(it).delete() }
    }

    private fun create(name: String, existingNames: MutableSet<String>): OutputWriter.Resolution.Ready {
        val file = File(dir, name)
        file.createNewFile()
        existingNames += name
        return OutputWriter.Resolution.Ready(Uri.fromFile(file), name)
    }
}
