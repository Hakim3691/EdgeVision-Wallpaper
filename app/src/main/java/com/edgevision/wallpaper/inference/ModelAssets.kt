package com.edgevision.wallpaper.inference

import android.content.Context
import java.io.File

object ModelAssets {

    const val ASSET_PATH = "models/hazard_efficientdet_lite0.tflite"
    private const val MIN_MODEL_BYTES = 1_000_000L
    private const val TARGET_NAME = "hazard_model.tflite"

    /**
     * TensorFlow Lite needs a real file path. Copy the bundled asset once into
     * no-backup internal storage, where it survives app updates but is not backed
     * up to the cloud.
     */
    @Synchronized
    fun ensureModel(context: Context): File {
        val target = File(context.noBackupFilesDir, TARGET_NAME)
        if (target.isFile && target.length() > MIN_MODEL_BYTES) {
            return target
        }

        val temporary = File(target.parentFile, "$TARGET_NAME.tmp")
        context.assets.open(ASSET_PATH).use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }

        check(temporary.length() > MIN_MODEL_BYTES) {
            "Bundled detection model is missing or truncated"
        }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Could not replace the cached detection model")
        }
        check(temporary.renameTo(target)) {
            temporary.delete()
            "Could not install the detection model"
        }
        return target
    }
}
