package pl.fehu.imageresizer.engine

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.fehu.imageresizer.domain.model.InputFormat

/** Probes real device decode support using the bundled `assets/probe` samples. */
@RunWith(AndroidJUnit4::class)
class FormatSupportTest {

    @Test
    fun heifProbe_succeedsOnApi28Plus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val support = FormatSupport.probe(context)
        assertTrue(support.isSupported(InputFormat.HEIF))
    }

    @Test
    fun avifProbe_matchesPlatformSupport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val support = FormatSupport.probe(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(support.isSupported(InputFormat.AVIF))
        } else {
            assertFalse(support.isSupported(InputFormat.AVIF))
        }
    }

    @Test
    fun alwaysAttemptedFormats_areReportedSupportedRegardlessOfProbe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val support = FormatSupport.probe(context)
        assertTrue(support.isSupported(InputFormat.JPEG))
        assertTrue(support.isSupported(InputFormat.PNG))
        assertTrue(support.isSupported(InputFormat.WEBP))
        assertTrue(support.isSupported(InputFormat.GIF))
        assertTrue(support.isSupported(InputFormat.BMP))
        assertTrue(support.isSupported(InputFormat.DNG))
    }

    @Test
    fun unknownFormat_isNeverSupported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val support = FormatSupport.probe(context)
        assertFalse(support.isSupported(InputFormat.OTHER))
    }
}
