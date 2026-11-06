package com.edgevision.wallpaper.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** Why a capture could not be stored. Every rejection is explicit. */
enum class SaveRejection {
    /** The camera produced no usable bytes. */
    EMPTY_IMAGE,

    /** The app is not allowed to write to shared storage. */
    PERMISSION_DENIED,

    /** MediaStore refused to create a row (storage unavailable). */
    STORAGE_UNAVAILABLE,

    /** The row was created but the JPEG could not be written. */
    WRITE_FAILED,

    /** The row was written but could not be published. */
    PUBLISH_FAILED,
}

sealed interface SaveResult {
    data class Saved(val displayName: String, val uri: String?) : SaveResult

    data class Rejected(val reason: SaveRejection) : SaveResult
}

/**
 * Narrow seam between the burst runner and storage. Tests supply a fake that
 * records exactly which files were created and which writes were rejected; the
 * production implementation writes to MediaStore.
 */
interface CaptureImageSink {
    fun save(
        jpeg: ByteArray,
        trigger: CaptureTrigger,
        capturedAtMs: Long,
        shotIndex: Int,
    ): SaveResult
}

/**
 * Storage operations, kept free of Android value types so the orchestration can
 * be specified on the JVM. The production gateway builds the ContentValues.
 */
interface MediaStoreGateway {
    fun insert(
        relativePath: String,
        displayName: String,
        mimeType: String,
        dateTakenMs: Long,
        pending: Boolean,
    ): String?

    fun write(uri: String, bytes: ByteArray): Boolean

    fun publish(uri: String): Boolean

    fun delete(uri: String)
}

/**
 * Writes a JPEG into the Android Clear capture collection.
 *
 * On API 29+ the row is inserted with `IS_PENDING = 1`, the bytes are written,
 * and only then is the row published, so a partially written file is never
 * visible to the gallery. If writing or publishing fails the row is deleted
 * instead of being left pending forever. Below API 29 the legacy `DATA` column
 * is used and the caller-supplied [canWrite] gate reports the storage
 * permission result.
 */
class MediaStoreImageSink(
    private val gateway: MediaStoreGateway,
    private val canWrite: () -> Boolean = { true },
) : CaptureImageSink {

    override fun save(
        jpeg: ByteArray,
        trigger: CaptureTrigger,
        capturedAtMs: Long,
        shotIndex: Int,
    ): SaveResult {
        if (jpeg.isEmpty()) return SaveResult.Rejected(SaveRejection.EMPTY_IMAGE)
        if (!canWrite()) return SaveResult.Rejected(SaveRejection.PERMISSION_DENIED)

        val displayName = CaptureTarget.displayName(trigger, capturedAtMs, shotIndex)
        val uri = gateway.insert(
            relativePath = CaptureTarget.RELATIVE_PATH,
            displayName = displayName,
            mimeType = CaptureTarget.MIME_TYPE,
            dateTakenMs = capturedAtMs,
            pending = true,
        ) ?: return SaveResult.Rejected(SaveRejection.STORAGE_UNAVAILABLE)

        if (!gateway.write(uri, jpeg)) {
            gateway.delete(uri)
            return SaveResult.Rejected(SaveRejection.WRITE_FAILED)
        }

        if (!gateway.publish(uri)) {
            gateway.delete(uri)
            return SaveResult.Rejected(SaveRejection.PUBLISH_FAILED)
        }

        return SaveResult.Saved(displayName, uri)
    }
}

/** Production gateway. Owns the Android-specific MediaStore details. */
class AndroidMediaStoreGateway(
    private val context: Context,
) : MediaStoreGateway {

    override fun insert(
        relativePath: String,
        displayName: String,
        mimeType: String,
        dateTakenMs: Long,
        pending: Boolean,
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.DATE_ADDED, dateTakenMs / 1_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, if (pending) 1 else 0)
            } else {
                // Legacy path: MediaStore has no RELATIVE_PATH before API 29.
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    relativePath.removePrefix("Pictures/").trimEnd('/'),
                )
                folder.mkdirs()
                put(MediaStore.Images.Media.DATA, File(folder, displayName).absolutePath)
            }
        }
        return try {
            context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?.toString()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Could not create a MediaStore row", throwable)
            null
        }
    }

    override fun write(uri: String, bytes: ByteArray): Boolean = try {
        context.contentResolver.openOutputStream(Uri.parse(uri), "w")?.use { stream ->
            stream.write(bytes)
            stream.flush()
            true
        } ?: false
    } catch (throwable: Throwable) {
        Log.w(TAG, "Could not write capture bytes", throwable)
        false
    }

    override fun publish(uri: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        return try {
            context.contentResolver.update(Uri.parse(uri), values, null, null) > 0
        } catch (throwable: Throwable) {
            Log.w(TAG, "Could not publish capture", throwable)
            false
        }
    }

    override fun delete(uri: String) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    private companion object {
        const val TAG = "AndroidClearCapture"
    }
}
