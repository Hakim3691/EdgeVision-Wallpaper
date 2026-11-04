package com.edgevision.wallpaper.camera

/**
 * Why the camera pipeline may not be capturing.
 *
 * A `WallpaperService` cannot raise a permission dialog, so a missing permission
 * used to leave an empty home screen with nothing but a log line. The engine now
 * resolves the access state, draws a notice on the wallpaper for the two states
 * the user has to resolve, and keeps polling for the permission to appear.
 */
enum class CameraAccess {

    /** Permission granted and camera hardware present: the pipeline can run. */
    READY,

    /** The user has not granted `CAMERA`; only the settings screen can fix this. */
    MISSING_PERMISSION,

    /** Permission granted but the device exposes no camera at all. */
    NO_HARDWARE;

    /** True when the pipeline should idle and wait for something outside its control. */
    val needsUserAction: Boolean
        get() = this != READY

    companion object {
        fun resolve(permissionGranted: Boolean, cameraAvailable: Boolean): CameraAccess = when {
            !permissionGranted -> MISSING_PERMISSION
            !cameraAvailable -> NO_HARDWARE
            else -> READY
        }
    }
}
