# Android Clear — production completion status

This report records what is implemented and verified, what was measured, and what
still requires a physical device. It deliberately separates "compiles and passes
specifications" from "works on hardware". No feature is claimed on the strength of
a class existing.

## Verified in this environment

Toolchain: Temurin JDK 21.0.13, Android SDK Platform 35, Build-Tools 35.0.0,
Gradle 8.9 wrapper, Python 3.12.3, Linux x86_64.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

- Debug APK builds.
- 9 JVM test classes pass, 0 failures, 0 errors.
- 4 of those classes are the new Given/When/Then specifications.

## Baseline repair

The repository's `main` history contained three commits that replaced working
files with non-compiling drafts, plus a duplicated `CaptureState` in a stray
`wallet` package. CI on the tip commit was red.

Restored baseline: commit `05337fd`, which is the last revision that builds and
passes tests. The restoration is a normal forward commit; the broken commits stay
in history for reference.

Defects repaired on the way back to green:

| Defect | Resolution |
| --- | --- |
| `strings.xml` unescaped `&` and a duplicate `wallpaper_picker_unavailable` | escaped and de-duplicated |
| Misplaced capture status strings with no implementation behind them | removed with the broken revision; rebuilt properly (see below) |
| Non-compiling `CaptureState.kt` / `HazardOverlayRenderer.kt` / `TfliteDetector.kt` | replaced by the green baseline and the new tested modules |

## Implemented and specification-tested

### Threat grading — `vision/ThreatLevel.kt`, `vision/ThreatDecisionEngine.kt`

Explicit priority: `NONE → INFO → LOW → MEDIUM → HIGH → CRITICAL`.

- A single frame can never produce a warning (verified by spec).
- Two agreeing frames are required before any warning grade.
- Grading combines area, expansion rate, confidence and persistence.
- A hazard that leaves the frame holds its level briefly, then decays.
- A different hazard kind restarts confirmation.
- Capture is admitted only at `HIGH` or above.

The wallpaper service now routes tracker output through the decision engine and
returns early below `MEDIUM`, so the screen cannot flash from one weak detection.

### Capture admission — `capture/CaptureController.kt`, `capture/CaptureTarget.kt`

Pure state machine covering arming, burst admission and cooldown:

- Automatic capture requires arming **and** `HIGH`/`CRITICAL`.
- Manual capture is admitted while automatic is disarmed.
- A running burst refuses a second burst (`BUSY`).
- Cooldown is enforced and reported (`cooldownRemainingMs`).
- A zero-shot configuration disables the shutter (`DISABLED`).
- MediaStore contract: `Pictures/Android Clear/Threat Captures/`, `image/jpeg`,
  file names namespaced `auto`/`manual`.

### Entitlements — `entitlement/Entitlement.kt`

Marketplace-independent, no fabricated purchases:

- `Tier` (`FREE`, `PRO`, `PREMIUM`) and a `Feature` enum declaring its minimum tier.
- `EntitlementProvider` per marketplace; missing/offline provider resolves to `FREE`.
- `BillingGateway.purchase()` returns `NotConfigured` in this build; there is no
  path that pretends a purchase succeeded.

### Performance modes — `diagnostics/PerformanceGovernor.kt`

`AUTOMATIC`, `BATTERY_SAVER`, `BALANCED`, `PERFORMANCE`, with thermal pressure,
battery level, charging state and system battery saver widening the interval.
Detection is never disabled — asserted across every mode/thermal combination.

### Settings persistence — `settings/AssistSettings.kt`

Added persisted capture arming, burst size, burst interval, capture cooldown and
performance mode, each clamped on read and write so a corrupt preference cannot
produce an invalid configuration.

## Measured

Debug APK (`app-arm64-v8a-debug.apk`): **37,208,062 bytes / 35.48 MiB**.

| Contributor | Bytes |
| --- | --- |
| `classes.dex` | 10,286,996 |
| `libpython3.12.so` | 6,697,104 |
| model `hazard_efficientdet_lite0.tflite` | 4,563,519 |
| `libcrypto_python.so` | 4,544,368 |
| Chaquopy `stdlib-common.imy` | 4,334,340 |
| `libtensorflowlite_jni.so` | 3,549,384 |

ABIs packaged: `arm64-v8a` only.

**Conclusion on size:** there is no artificial padding, dummy asset or duplicate
ABI. The dominant artificial weight is the Chaquopy Python runtime
(`libpython` + `libcrypto` + `libssl` + `libsqlite3` + Chaquopy stdlib ≈ 19 MB).
Removing Chaquopy in favour of the Kotlin TFLite interpreter is the single
legitimate size lever; it must be done with a working Kotlin detector, not by
deleting the Python pipeline and silently losing detection.

The 40 MiB CI check is a regression gate, not a cap on functionality. It should be
re-derived from a measured release build rather than treated as a target.

## Not implemented — requires further work

These are explicit gaps, not hidden limitations. Each needs implementation and a
device test before it can be described as working.

1. **Camera burst execution.** `CaptureController` decides *whether* a burst may
   run; the Camera2 still-capture writer (JPEG `ImageReader`, MediaStore insert,
   3-shot sequencing on the live session) is not implemented. Triple-tap arming is
   not wired to the wallpaper engine. Nothing currently writes an image.
2. **Low-latency display path.** The preview still goes
   Camera2 → `YUV_420_888` → Bitmap → software canvas. A `SurfaceTexture`/EGL
   path has not been built, and no latency comparison has been measured, because
   the emulator and this sandbox have no camera.
3. **Dashboard, onboarding, diagnostics screen.** The activity remains the
   settings screen from the baseline. No new UI was shipped in this change.
4. **Performance governor wiring.** The governor is implemented and specified but
   the camera pipeline does not yet consume its interval; `CameraController` has no
   cadence setter in this baseline.
5. **Entitlement UI.** No paywall or purchase screen exists, by design: there is no
   verified billing integration to back one.
6. **Physical-device validation.** Latency, frame pacing, thermal behaviour,
   battery impact and burst capture all require an arm64 device.

## Honest summary

The project now builds, is covered by executable Given/When/Then specifications
for its new decision layers, and has an explicit, testable safety rule: nothing
flashes, sounds or captures from one weak detection. The camera burst writer, the
GPU display path and the product UI are still missing, and are listed above rather
than described as complete.
