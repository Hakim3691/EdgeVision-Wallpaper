# Android Clear — production completion status

This report records what is implemented and verified, what was measured, and what
still requires a physical device. It deliberately separates "compiles and passes
specifications" from "works on hardware". No feature is claimed on the strength of
a class existing, and no latency figure is claimed at all: nothing in this
environment can measure camera latency.

## Verified in this environment

Toolchain: Temurin JDK 21.0.13, Android SDK Platform 35, Build-Tools 35.0.0,
Gradle 8.9 wrapper, Python 3.12.3, Linux x86_64.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

- `:app:assembleDebug` — **BUILD SUCCESSFUL**.
- `:app:testDebugUnitTest` — **71 tests, 13 classes, 0 failures, 0 errors**.
  Eight of those classes are executable Given/When/Then specifications and
  account for 46 of the tests.
- `:app:assembleRelease` — **BUILD SUCCESSFUL**, with and without
  `-PallowDebugReleaseSigning=true` (debug signing is allowed locally; in CI the
  real release key comes from `ANDROID_KEYSTORE_BASE64`).
- Lint vital for release ran as part of `assembleRelease` and reported no errors.

| Artifact | Size |
| --- | --- |
| `app-arm64-v8a-debug.apk` | 36,219,538 bytes / 34.54 MiB |
| `app-arm64-v8a-release.apk` | 35,081,676 bytes / 33.46 MiB |

ABIs packaged: `arm64-v8a` only.

## Baseline repair

The repository's `main` history contained three commits that replaced working
files with non-compiling drafts, plus a duplicated `CaptureState` in a stray
`wallet` package. CI on the tip commit was red.

Restored baseline: commit `05337fd`, which is the last revision that builds and
passes tests. The restoration is a normal forward commit; the broken commits stay
in history for reference. This change builds forward from the verified commit
`13de0ea`.

Defects repaired on the way back to green:

| Defect | Resolution |
| --- | --- |
| `strings.xml` unescaped `&` and a duplicate `wallpaper_picker_unavailable` | escaped and de-duplicated |
| Misplaced capture status strings with no implementation behind them | removed with the broken revision; rebuilt properly (see below) |
| Non-compiling `CaptureState.kt` / `HazardOverlayRenderer.kt` / `TfliteDetector.kt` | replaced by the green baseline and the new tested modules |

## Threat capture, end to end

### What is wired

`CameraWallpaperService` now owns a real capture pipeline, not a status flag.

1. **JPEG acquisition — `camera/CameraController.kt`.** The Camera2 session is
   created with two outputs: the existing `YUV_420_888` detection stream and a
   JPEG `ImageReader` sized from
   `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(ImageFormat.JPEG)`, capped at
   the preview size. `captureJpeg { bytes -> }` issues a
   `TEMPLATE_STILL_CAPTURE` request against the running session and copies the
   JPEG plane into a `ByteArray`; the reader is closed with the session. If the
   session is gone, the callback receives `null` instead of the burst hanging.
2. **Storage — `capture/CaptureImageSink.kt`.** `MediaStoreImageSink` writes to
   `Pictures/Android Clear/Threat Captures/` (`CaptureTarget.RELATIVE_PATH`) with
   `image/jpeg` and a sortable `DISPLAY_NAME` (`android-clear-<auto|manual>-<stamp>-<n>.jpg`),
   `DATE_TAKEN`/`DATE_ADDED`. On API 29+ the row is inserted with
   `IS_PENDING = 1`, the bytes are written, then the row is published — a partial
   file is never visible to a gallery. A failed write or publish **deletes** the
   row instead of leaving it pending. Below API 29 the legacy `DATA` path is used
   and the storage permission is checked through the sink's `canWrite` gate; the
   manifest declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`.
3. **Burst sequencing — `capture/BurstCaptureRunner.kt`.** Shots run sequentially:
   shot *N*'s save completion schedules shot *N+1* after `intervalMs` through an
   injected scheduler (the service passes `Handler.postDelayed`). A rejected or
   empty capture aborts the remaining shots and records the reason; it never
   burns battery producing images that cannot be stored.
4. **Admission authority — `capture/CaptureController.kt`.** The runner asks the
   controller for every burst; the controller remains the single owner of
   arming, `HIGH`/`CRITICAL` threshold, `BUSY`, cooldown and `DISABLED`. The
   capture path adds no second copy of those rules.
5. **Service wiring — `wallpaper/CameraWallpaperService.kt`.** On every graded
   threat the engine calls `burstRunner.onThreat(level, now)`; `BurstCaptureRunner`
   reports `Completed`/`Aborted` and the service logs both.

The seam that makes this testable is `MediaStoreGateway` (insert / write /
publish / delete over primitives) plus `CaptureImageSink`. The Android-specific
`AndroidMediaStoreGateway` is the only place that builds `ContentValues`.

### What the specifications prove

- `bdd/BurstCaptureSpec` — arm → three shots at the configured interval →
  cooldown → `BUSY` → permission-denied abort → manual burst while disarmed,
  with a fake sink asserting the exact file names produced and that an empty
  frame is rejected. Delay list asserted as `[350, 350]`.
- `bdd/MediaStoreSinkSpec` — correct relative path, MIME, pending-on-insert,
  publish exactly once, delete on write failure and on publish failure, and no
  gateway calls at all when the permission gate refuses.

### What still requires a device

The specifications exercise the orchestration against a fake sink and a fake
clock. The following are **not** verified here, because this sandbox has no
camera and no Android runtime:

- that a real sensor produces a non-empty JPEG for `TEMPLATE_STILL_CAPTURE`, and
  the resulting file opens in a gallery;
- that MediaStore accepts the insert and that the published row appears in
  `Pictures/Android Clear/Threat Captures/` on a real device (API 29+ scoped
  storage and legacy API 28 behaviour differ);
- the runtime permission prompt on API ≤ 28 and the resulting `PERMISSION_DENIED`
  path;
- burst timing as perceived by the sensor, autofocus settling, and behaviour when
  the camera is in use by another app.

## Triple-tap arming

`capture/TripleTapArming.kt` is pure and time-injected. The service enables
engine touch events and forwards `ACTION_DOWN` with
`SystemClock.elapsedRealtime()`.

- Three taps inside a 600 ms window arm capture for 8 s
  (`TripleTapArming.armDurationMs`); the service stores `settingArmed`, sets the
  controller armed, and posts one expiry callback for the remaining duration.
- A triple tap that is too slow does not arm; the stale taps are discarded.
- Taps while already armed are `Ignored(ALREADY_ARMED)` — a fourth tap cannot
  double-arm or extend the window.
- Taps while capture is switched off are `Ignored(DISABLED)` and leave no
  partial count behind.
- Expiry calls `onTimePassed`, which disarms and reports the expiry once.
  `cancel()` disarms explicitly; the service calls it when the wallpaper becomes
  hidden, when the engine is destroyed, and when capture is disabled in settings.

`bdd/TripleTapArmingSpec` covers all six behaviours above (7 tests).

**Device-dependent:** whether the launcher delivers touch events to a wallpaper
engine on a given OEM build. The service requests touch events and handles
`ACTION_DOWN`; that a specific launcher forwards them cannot be checked here.

## Governor actually governs

`diagnostics/PerformanceController.kt` is the seam. The service sets mode,
thermal pressure (`PowerManager.currentThermalStatus` on API 29+, with an
unregistered-on-destroy thermal listener), battery percentage, charging state and
system battery-saver state, then calls `refresh()`. When the resulting profile
changes, `apply` runs — in production it calls
`CameraController.setDetectionIntervalMs(profile.inferenceIntervalMs)`, which
changes the real throttle in `handleImage` (previously a fixed 66 ms constant).

| State | Effective detection interval |
| --- | --- |
| `PERFORMANCE` | 66 ms |
| `BALANCED`, or `AUTOMATIC` while charging / above 40 % | 100 ms |
| `BATTERY_SAVER`, power save on, or ≤ 15 % unplugged | 250 ms |
| Thermal `SEVERE` | 500 ms |
| Thermal `CRITICAL` | 1000 ms |

Detection is never disabled: `PerformanceProfile.detectionEnabled` is always
true, the slowest state is 1000 ms, and `setDetectionIntervalMs` clamps to a
positive floor. `bdd/EffectiveCadenceSpec` proves the interval applied through the
controller changes with mode and thermal state (66/250/500/1000), that repeated
refreshes with unchanged state apply once, and that detection stays enabled.
`bdd/PerformanceGovernorSpec` asserts detection is never disabled across every
mode/thermal combination.

**Device-dependent:** whether the hardware actually sustains those cadences, and
real thermal/battery transitions. No latency or frame-rate measurement is claimed.

## Other implemented and specification-tested layers

### Threat grading — `vision/ThreatLevel.kt`, `vision/ThreatDecisionEngine.kt`

`NONE → INFO → LOW → MEDIUM → HIGH → CRITICAL`. A single frame can never produce a
warning; two agreeing frames are required; grading combines area, expansion rate,
confidence and persistence; a hazard that leaves the frame holds briefly then
decays; a different hazard kind restarts confirmation. Capture is admitted only at
`HIGH` or above.

### Entitlements — `entitlement/Entitlement.kt`

Marketplace-independent, no fabricated purchases. `BillingGateway.purchase()`
returns `NotConfigured`; there is no path that pretends a purchase succeeded.

### Settings persistence — `settings/AssistSettings.kt`

Persisted capture arming, burst size, burst interval, capture cooldown and
performance mode, each clamped on read and write.

### CI/CD

`.github/workflows/android-ci.yml`:

- `bdd-specs` runs `:app:testDebugUnitTest --tests "com.edgevision.wallpaper.bdd.*"`
  — the new capture, triple-tap and cadence specifications fail the build if they
  regress.
- `jvm-and-ui-tests` runs the full unit suite (69 tests, including the
  specifications).
- `build-apks` needs every test job, builds debug and release, enforces the 40 MiB
  cap and rejects any non-arm64 ABI.

## Size

Debug 34.54 MiB, release 33.46 MiB. The dominant weight is the Chaquopy Python
runtime (`libpython` + `libcrypto` + `libssl` + `libsqlite3` + stdlib ≈ 19 MB),
followed by the 4.56 MB TFLite model and the TFLite JNI library. There is no
padding, dummy asset or duplicate ABI. Removing Chaquopy in favour of the Kotlin
TFLite interpreter is the single legitimate size lever; it must be done with a
working Kotlin detector, not by deleting the Python pipeline and silently losing
detection. The 40 MiB CI check is a regression gate, not a functional target.

## Capture and cadence settings screen

The settings activity now exposes the persisted capture contract the service
reads: arm/disarm automatic capture, images per burst (1–5), gap between images,
cooldown between bursts, and the detection-cadence mode (Auto / Battery /
Balanced / Fast). Values are clamped by `AssistSettings` as before. On API 28 and
below a "Grant storage access" button appears when the legacy write permission is
missing, because capture cannot store anything there without it (API 29+ needs no
storage permission). `MainActivityEspressoTest` (Robolectric) toggles the arming
switch and selects Battery mode, asserting both reach `AssistSettings` — the same
values the wallpaper engine reads when it next builds its capture controller.

## Still not implemented

1. **Manual capture trigger.** Manual bursts exist in the controller and runner
   (`onManualRequest`) but no UI or gesture invokes one, and there is no gallery
   or history screen.
2. **Low-latency display path.** The preview still goes Camera2 → `YUV_420_888` →
   Bitmap → software canvas. No `SurfaceTexture`/EGL path, and no latency
   comparison has been measured, because no camera is available.
3. **Entitlement UI.** No paywall or purchase screen, by design: there is no
   verified billing integration to back one.
4. **Physical-device validation.** Latency, frame pacing, thermal behaviour,
   battery impact, burst capture and launcher touch delivery all require an
   arm64 device.

## Honest summary

Threat capture is now wired: the service asks Camera2 for real JPEGs, stores them
through an injectable MediaStore sink with correct path/MIME/name/date and
`IS_PENDING` cleanup, sequences bursts through the existing admission controller,
arms from a pure triple-tap gesture, exposes the capture and cadence settings the
engine reads, and drives the live detection cadence from the performance governor. Debug, unit specifications and release all pass in this
environment. What cannot be claimed is that a physical phone has produced and
saved a photo: that requires hardware, and the behaviours listed above as
device-dependent are exactly the ones this environment cannot execute.
