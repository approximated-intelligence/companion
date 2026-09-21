package de.perigon.companion.media.transform

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileDescriptor

/**
 * Metadata carried from a source photo onto a JPEG we produced.
 *
 * A whitelist, because ExifInterface cannot enumerate tags. Left out on
 * purpose:
 *  - Orientation: our pixels are upright; the output gets NORMAL.
 *  - pixel dimensions, thumbnails, MPF: they describe the source's bytes.
 *  - XMP: on phones it carries motion-photo and Ultra HDR gain-map
 *    pointers to data the output no longer contains.
 *  - UNDEFINED-format tags (UserComment, GPSProcessingMethod): their
 *    charset prefix does not survive a string round trip.
 */
internal val PRESERVED_EXIF_TAGS: List<String> = listOf(
    // time
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    // place
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_MAP_DATUM,
    // device
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_LENS_SPECIFICATION,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    ExifInterface.TAG_CAMERA_OWNER_NAME,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_IMAGE_UNIQUE_ID,
    // exposure
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_BRIGHTNESS_VALUE,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_SENSITIVITY_TYPE,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
)

/** Pure over parsed metadata: the whitelisted tags present in [source]. */
fun preservedExif(source: ExifInterface): Map<String, String> =
    PRESERVED_EXIF_TAGS
        .mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }
        .toMap()

/**
 * Read the whitelisted tags from a seekable descriptor (SAF documents are).
 * A descriptor rather than a stream: HEIF metadata needs random access.
 * Throws if the format cannot be parsed.
 */
fun readPreservedExif(fd: FileDescriptor): Map<String, String> =
    preservedExif(ExifInterface(fd))

/**
 * Write [tags] onto a JPEG file, Orientation forced to NORMAL — the pixels
 * were decoded upright, so a copied rotation would turn them again in
 * every viewer. Throws on failure.
 */
fun writeExif(target: File, tags: Map<String, String>) {
    val exif = ExifInterface(target)
    tags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    exif.saveAttributes()
}
