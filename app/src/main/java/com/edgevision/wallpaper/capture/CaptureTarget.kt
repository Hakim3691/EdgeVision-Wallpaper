package com.edgevision.wallpaper.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where burst photos go. Kept as a small contract object so the storage
 * location is defined once and can be asserted by specifications instead of
 * being repeated as a string literal in the writer.
 */
object CaptureTarget {

    /** Relative path used with MediaStore.Images.Media.RELATIVE_PATH (API 29+). */
    const val RELATIVE_PATH = "Pictures/Android Clear/Threat Captures/"

    const val MIME_TYPE = "image/jpeg"

    /** Quality for the JPEG burst. High enough to identify a threat, small enough to stay light. */
    const val JPEG_QUALITY = 92

    private const val FILE_PREFIX = "android-clear"

    /**
     * Stable, sortable name: trigger and timestamp first so a gallery sorts a
     * burst in capture order and automatic captures are distinguishable from
     * manual ones by name as well as by metadata.
     */
    fun displayName(trigger: CaptureTrigger, capturedAtMs: Long, shotIndex: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(capturedAtMs))
        val kind = when (trigger) {
            CaptureTrigger.THREAT_AUTO -> "auto"
            CaptureTrigger.MANUAL -> "manual"
        }
        return "$FILE_PREFIX-$kind-$stamp-${shotIndex + 1}.jpg"
    }
}
