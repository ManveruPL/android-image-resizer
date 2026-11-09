package pl.fehu.imageresizer.work

import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger
import pl.fehu.imageresizer.domain.model.InputFormat
import pl.fehu.imageresizer.domain.model.JobConfig
import pl.fehu.imageresizer.engine.ImageProcessor
import pl.fehu.imageresizer.engine.ProcessedImage

/**
 * Wraps a real [ImageProcessor], throwing `OutOfMemoryError` for [failUri] the first
 * [failures] calls and delegating otherwise -- simulates application-plan.md M4's OOM-retry AC
 * without needing an actual low-memory device (AGENTS.md: "work/: use a fake ImageProcessor to
 * simulate OOM and failures").
 */
class FlakyImageProcessor(
    private val delegate: ImageProcessor,
    private val failUri: Uri,
    private val failures: Int = 1,
) : ImageProcessor {

    val callCountForFailUri = AtomicInteger(0)

    override fun process(
        inputUri: Uri,
        outputUri: Uri,
        inputFormat: InputFormat,
        config: JobConfig,
        lowRamDevice: Boolean,
    ): ProcessedImage {
        if (inputUri == failUri) {
            val call = callCountForFailUri.incrementAndGet()
            if (call <= failures) throw OutOfMemoryError("simulated for test (call $call)")
        }
        return delegate.process(inputUri, outputUri, inputFormat, config, lowRamDevice)
    }
}
