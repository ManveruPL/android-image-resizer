package pl.fehu.imageresizer.engine

import androidx.exifinterface.media.ExifInterface
import pl.fehu.imageresizer.domain.model.MetadataPolicy

/**
 * Copies an allow-listed set of EXIF tags between already-open [ExifInterface] instances and
 * normalizes orientation/pixel-dimension tags on the output. Call off the main thread (does EXIF
 * file I/O). Never throws: metadata problems are reported via the returned note instead of
 * failing the item (application-plan.md §5.4).
 */
object MetadataCopier {

    private val GPS_TAGS = setOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    private val BASE_TAGS = setOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_ARTIST,
    )

    fun allowListFor(policy: MetadataPolicy): Set<String> = when (policy) {
        MetadataPolicy.KEEP_ALL -> BASE_TAGS + GPS_TAGS
        MetadataPolicy.STRIP_GPS -> BASE_TAGS
        MetadataPolicy.STRIP_ALL -> emptySet()
    }

    /**
     * Copies allow-listed tags from [sourceExif] (null if the source has none, or couldn't be
     * read) into [outputExif], always normalizes orientation to `NORMAL` and sets the pixel
     * dimension tags to [finalWidth]/[finalHeight], then saves. Returns a human-readable note on
     * failure, or null on success.
     */
    fun copy(
        sourceExif: ExifInterface?,
        outputExif: ExifInterface,
        policy: MetadataPolicy,
        finalWidth: Int,
        finalHeight: Int,
    ): String? = try {
        if (sourceExif != null) {
            for (tag in allowListFor(policy)) {
                sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
            }
        }
        outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        outputExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, finalWidth.toString())
        outputExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, finalHeight.toString())
        outputExif.saveAttributes()
        null
    } catch (e: Exception) {
        "metadata copy failed: ${e.message}"
    }
}
