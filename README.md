# Android Clear

A transparent Android live wallpaper that streams the rear camera through the home
screen and runs on-device hazard detection with a bundled TFLite model. Nearby
vehicles and vertical street fixtures trigger a pulsing red border, a low-latency
tone, and haptics. Blind Assist Mode replaces the visual flash with speech and a
stronger vibration pattern.

All inference happens locally. Frames are never uploaded and the app requests only
`CAMERA` and `VIBRATE`.

> Safety notice: this is an assistive prototype, not a certified mobility or
> driving aid. Do not rely on it to avoid traffic, obstacles, or other hazards.

## Architecture

```
Camera2 (rear, YUV_420_888, capped 1280x720 @ <=30 fps)
  └─ ImageReader
       └─ YuvFrameConverter (off the main thread)
            ├─ preview Bitmap (512 px wide) ──► SurfaceHolder (translucent)
            │                                   └─ pulsing red Paint.Style.STROKE border
            └─ 320x320 RGB bytes ──► HazardInferenceEngine (Chaquopy)
                                      └─ Python hazard_detector
                                           └─ EfficientDet-Lite0 TFLite
                                                └─ JSON {label, hazard, score, box}
                                                     └─ HazardTracker (area + expansion)
                                                          └─ AlertPolicy
                                                               ├─ visual border
                                                               ├─ ToneGenerator
                                                               ├─ Vibrator
                                                               └─ TextToSpeech (Blind Assist)
```

Key files:

| Path | Purpose |
| --- | --- |
| `wallpaper/CameraWallpaperService.kt` | `WallpaperService` engine, surface and pipeline wiring |
| `wallpaper/WallpaperEngineController.kt` | Android-free visibility/surface state machine |
| `wallpaper/HazardOverlayRenderer.kt` | Transparent preview and pulsing alert border |
| `camera/CameraController.kt` | Camera2 session, 720p/30 fps cap, synchronous release |
| `camera/YuvFrameConverter.kt` | YUV to preview bitmap + 320x320 RGB downsample |
| `inference/HazardInferenceEngine.kt` | Python worker, frame dropping, pause/release |
| `inference/TfliteBridge.kt` | Interpreter bridge callable from Python |
| `vision/HazardTracker.kt` | Proximity and expanding-box logic |
| `alert/AlertPolicy.kt` | Visual vs. accessible alert channel decisions |
| `app/src/main/python/hazard_detector.py` | Python inference, decode, hazard taxonomy |
| `app/src/main/assets/models/hazard_efficientdet_lite0.tflite` | Bundled model (provisioned by a pinned downloader) |

### Lifecycle and battery behavior

`WallpaperService.Engine.onVisibilityChanged` feeds
`WallpaperEngineController`. The controller runs hardware only when **both** the
wallpaper is visible and the surface exists. On `onVisibilityChanged(false)` it
stops inference first, then closes the `CameraCaptureSession`, `CameraDevice`,
and `ImageReader` synchronously. Pending Python work is invalidated, the detector
interpreter is released, the pulse loop is cancelled, and audio/TTS resources are
released. Returning to the launcher starts the camera and Python session again.

The camera stream is capped at 1280x720. Frame conversion is throttled to ~15 fps
and inference drops frames while the single worker is busy, so thermal load stays
bounded. Detector input is a center-cropped 320x320 RGB buffer.

### Hazard behavior

The Kaggle/TF Hub EfficientDet-Lite0 COCO label map is mapped to two hazard kinds:

- **Vehicle:** bicycle, car, motorcycle, bus, train, truck
- **Pole / vertical obstacle:** traffic light, fire hydrant, stop sign, parking meter

A hazard fires when a detection reaches `minConfidence = 0.55` **and** either:

- its normalized box area is at least `0.12`, or
- its area is at least `0.06` and it grew by at least `1.5x` over a previous
  matching box within 900 ms (rapid approach).

Thresholds live in `vision/HazardPolicy.kt`. Alert channels are selected by
`alert/AlertPolicy.kt`: normal mode uses the red border and respects the sound and
vibration switches; Blind Assist always suppresses the visual flash and keeps a
tone, vibration, and spoken "Vehicle approaching" / "Obstacle ahead" alert.

## Mission status

The project currently builds green and its decision layers are covered by
executable Given/When/Then specifications that run in `:app:testDebugUnitTest`
and in the dedicated `bdd-specs` CI job. See
[docs/ANDROID_CLEAR_MISSION.md](docs/ANDROID_CLEAR_MISSION.md) for the verified
state, measured APK composition and the explicit list of work that still needs a
physical device (camera burst writer, GPU display path, dashboard/onboarding).

Measured debug APK: 35.48 MiB (arm64-v8a only). The dominant non-functional
weight is the Chaquopy Python runtime (~19 MB); there is no artificial padding.

## Requirements

- JDK 21 (Temurin recommended)
- Android SDK Platform 35 and Build Tools 35.0.0
- Python 3.12 on the **build machine** (Chaquopy requires the app Python version to
  match the build Python version)
- Gradle 8.9 (the wrapper JAR is provisioned by `tools/bootstrap.sh`)
- Android device/emulator with API 26+ and a rear camera for real hardware testing
- Network access on the first build to provision the pinned model and wrapper

Create `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

## Build and run

Provision the pinned build-time binaries first. The 4.5 MB model and the Gradle
wrapper JAR are downloaded and SHA-256 verified rather than stored in Git:

```bash
bash tools/bootstrap.sh
```

Then build:

```bash
bash ./gradlew :app:assembleDebug
```

Artifact: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

Install and open **Android Clear**, grant camera access, tap **Set live wallpaper**,
and pick Android Clear in the system wallpaper picker. The settings screen is also
reachable from the wallpaper picker's settings action.

Release build (arm64-v8a only):

```bash
export EDGEVISION_RELEASE_STORE_FILE=/secure/release.jks
export EDGEVISION_RELEASE_STORE_PASSWORD=...
export EDGEVISION_RELEASE_KEY_ALIAS=edgevision
export EDGEVISION_RELEASE_KEY_PASSWORD=...
bash ./gradlew :app:assembleRelease
```

Artifact: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`. Without the
environment variables, a local release build falls back to the Android debug key.

### Tests

```bash
# Python decoder/taxonomy tests (stdlib only, no Android)
python3 -m unittest discover -s tools/tests -v

# JVM tests: lifecycle Gherkin scenarios, hazard policy, parser, Robolectric Espresso UI
bash ./gradlew :app:testDebugUnitTest

# Instrumented Espresso + lifecycle tests (requires a running emulator/device)
bash ./gradlew :app:connectedDebugAndroidTest -PincludeX86Abi=true
```

The `includeX86Abi` property adds `x86_64` only to debug builds so a GitHub-hosted
emulator can install the app. Release builds and the default debug artifact contain
only `arm64-v8a`.

## CI/CD

`.github/workflows/android-ci.yml` runs on pushes to `main`, pull requests, and
manual dispatch:

1. Python detector unit tests.
2. JVM unit tests plus Espresso-on-Robolectric UI tests.
3. Instrumented Espresso tests on an API 34 x86_64 emulator.
4. Signed debug and release arm64 builds, the 40 MiB size gate, and artifact upload.

Every Gradle job runs `bash tools/bootstrap.sh` before invoking the wrapper, so
CI uses the same pinned model and wrapper checksums as a local build.

Gradle uses Java 21 (Temurin), configuration caching, build caching, and
`org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC`.

Keystores are provisioned with `keytool` on the runner. For a store-ready release
key, configure these repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the `.jks` file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If `ANDROID_KEYSTORE_BASE64` is absent, CI generates an ephemeral release key so the
artifact is signed, but that key is not suitable for store uploads.

`scripts/check_apk_size.sh` fails the job if either APK exceeds 40 MiB, and the
workflow separately rejects non-arm64 libraries in the release APK.

## Model

`app/src/main/assets/models/hazard_efficientdet_lite0.tflite`

The model is not committed to Git. `tools/fetch_model.sh` downloads it from the
official TensorFlow URL and verifies the SHA-256 in `tools/model.sha256`.
`app/build.gradle.kts` fails the build with an actionable message if the file is
missing or truncated. The provisioned binary is bundled into the final APK.

- Official TensorFlow Lite EfficientDet-Lite0 detection model with metadata
  (COCO, 320x320 uint8 input, 25 postprocessed detections).
- 4,563,519 bytes (< 5 MB). Weights are already int8-quantized; activations and
  outputs are float32. This is smaller and faster on CPU than an FP16 export, so
  the pipeline uses this artifact rather than the PRD's FP16 suggestion. The
  loader is model-agnostic: drop in another 320x320 EfficientDet-style TFLite file
  and update `tools/model.sha256` if FP16 is required.
- Source and checksum are recorded in `tools/fetch_model.sh` and
  `tools/model.sha256`. Re-download and verify with:

```bash
bash tools/fetch_model.sh
```

### TFLite runtime note

The PRD specifies Python `tflite-runtime`. Chaquopy's Android wheel repository
currently publishes `tflite-runtime` only for **Python 3.8 / TFLite 2.5 (2021)**,
while this project uses Chaquopy 17.2 with Python 3.12. Pinning the old runtime
would mean shipping an EOL Python plus a runtime that predates current Android
16 KB page-size requirements.

Instead, `hazard_detector.py` keeps Python as the inference pipeline and uses the
first available backend:

1. `tflite_runtime.interpreter.Interpreter`, if a compatible wheel is bundled
   (works on desktop/custom builds), or
2. `TfliteBridge`, a thin Kotlin wrapper around the official TensorFlow Lite Java
   runtime, called through Chaquopy's Java bridge.

Preprocessing, output decoding, filtering, hazard mapping, and the JSON contract
remain in Python. TensorFlow itself is never bundled. To use literal
`tflite-runtime`, pin Chaquopy 16.1 + Python 3.8 and add
`pip { install("tflite-runtime") }`; the Python backend is already implemented.

## APK size

The release APK must stay below 40 MiB. The bundled pieces are the Chaquopy Python
runtime, the TensorFlow Lite AAR, the 4.56 MB model, and AndroidX. Only
`arm64-v8a` is packaged. CI enforces the limit; locally:

```bash
bash scripts/check_apk_size.sh app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## Known limitations

- **Background camera access:** Android 9+ restricts camera use from background
  components. A `WallpaperService` usually remains bound while the launcher is
  visible, but some OEM builds may still deny camera access. The wallpaper fails
  closed (transparent home screen, no crash), and the camera error is logged. A
  foreground-service/notification workaround is not implemented because it adds a
  persistent notification and restricted background-start behavior; validate on
  your target devices.
- The COCO model has no bespoke `pole` class. Pole-like hazards come from the
  mapped street-fixture classes above; custom classes require retraining.
- Detection is throttled and frame-dropping by design. Alerts are advisory and may
  lag a fast approach.
- 16 KB page-size compatibility of the Chaquopy/TFLite native libraries has not
  been validated on Android 15+ hardware in this environment.
- The wallpaper is a prototype: it cannot provide depth, distance in metres, or
  reliable performance in low light.
