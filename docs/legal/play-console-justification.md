# Google Play Console submission — Android Clear

Applies to `com.edgevision.wallpaper`, version 1.4.0 (`versionCode` 5).

## 1. Data safety declaration

The app processes camera frames entirely on the device and declares no network
permission, so these answers follow from the code rather than from intent:

| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | Frames are processed in RAM and never leave the device. There is no account, no telemetry and no analytics SDK. |
| Is all user data encrypted in transit? | **N/A** | The app performs no network transmission. |
| Do you provide a way for users to request data deletion? | **N/A** (no server-side data) | Local state is removed by clearing app data or uninstalling. Threat captures are the user's own files in MediaStore and are deleted from any gallery. |
| Data types to declare | **None** | Camera access is a device permission, not data collection. |
| Crash logs / diagnostics | **None** | No Crashlytics, Firebase or equivalent is bundled. |

If a crash reporter or analytics SDK is added later, this declaration must be
updated in the same release: crash logs and diagnostics become "collected" even
when they carry no account identifier.

## 2. Background camera access justification

Use this text for the prominent disclosure and reviewer instructions:

> **Core feature.** Android Clear is a transparent live wallpaper that provides
> ambient, real-time hazard detection for users.
>
> **Why background camera access is necessary.** The purpose of a live wallpaper
> is to operate passively on the home screen and behind other applications. To
> detect physical hazards in the user's environment in real time, the app requires
> camera access while it is not the foreground activity. Foreground-only camera
> access would force the user to keep the app open as a standard application,
> which defeats its utility as an ambient, non-intrusive safety tool.
>
> **Privacy safeguards.** All video frames are processed entirely on-device by a
> bundled TensorFlow Lite model (EfficientDet-Lite0) running through an embedded
> Python runtime. No video, audio or image data is recorded, uploaded or
> transmitted off the device. The app declares no network permission. Automatic
> threat capture is off by default; when the user enables it, a JPEG burst is
> written to the device's own picture storage through MediaStore and is never
> uploaded.
>
> **Why alternatives are not applicable.** A foreground-only implementation
> cannot run behind the launcher, which is the entire interaction model of a live
> wallpaper. The camera is also released whenever the screen is off or the
> keyguard is showing, so it is never held while the device is idle.

## 3. Prominent disclosure evidence

Reviewers look for disclosure *before* the permission request, not only in a
policy document:

- The first tap on **Grant camera access** opens the in-app rationale dialog
  (`camera_rationale_title` / `camera_rationale_body`) with **Grant Camera
  Permission** and **Not Now**. Only the positive action triggers the system
  permission prompt.
- A permanent denial opens a settings fallback that links to the app's system
  page.
- The same rationale text is available at any time under **About & Legal**.
- The first launch shows the AI Detection Disclosure, and enabling automatic
  threat capture for the first time requires an explicit safety acknowledgement
  (checkbox + **Accept & Continue**, disabled until checked).

## 4. Notifications

The app does **not** request `POST_NOTIFICATIONS` and does not run a foreground
service, so no notification rationale or foreground-service declaration is
required. The supplied notification rationale document is therefore not
implemented; adding a camera foreground service later would require both that
prompt and a `FOREGROUND_SERVICE_CAMERA` declaration in the manifest.

## 5. Store listing text

Short description: *A transparent live wallpaper that detects nearby vehicles and
obstacles on-device.*

Full description must include the safety limitation verbatim from
`safety_notice.txt`: Android Clear is an informational tool, not a life-safety
device, does not contact emergency services, and detection is probabilistic.
