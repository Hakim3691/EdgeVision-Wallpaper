# Disclaimers and permission copy — implementation map

Source: *Android Clear — In-App Disclaimers & Permission Rationale
Specifications*. Each requirement is mapped to the code that satisfies it, or to
the reason it does not apply.

## Section 1 — In-app disclaimers

| Requirement | Status | Where |
| --- | --- | --- |
| AI detection disclaimer on first launch | Implemented | `MainActivity.maybeShowLegalOnboarding()` shows `ai_disclosure_title` / `ai_disclosure_dialog_body` once, gated by `AssistSettings.aiDisclosureSeen` |
| Accessible any time via About & Legal → AI Detection Disclosure | Implemented | `LegalActivity`, backed by `assets/legal/ai_disclosure.txt` |
| CTA "I Understand" | Implemented | `action_understand` |
| Secondary link to full documentation | Implemented | `action_read_more` opens `LegalActivity` |
| Safety notice before enabling monitoring the first time | Implemented | Capture switch reverts and opens `showSafetyNotice()` until accepted; `AssistSettings.safetyNoticeAccepted` |
| Checkbox acknowledgement required | Implemented | `dialog_safety_ack.xml`; positive button disabled until checked |
| "Accept & Continue" disabled until checked | Implemented | `dialog.getButton(BUTTON_POSITIVE).isEnabled` wired to the checkbox |
| Cancel action | Implemented | `action_cancel`; capture stays off |
| Performance/battery banner in Settings | Implemented | `card_power_limit`, shown when `PowerManager.isPowerSaveMode` and not dismissed (`AssistSettings.powerNoticeDismissed`) |
| "Open System Settings" from the banner | Implemented | Opens the app's system details page |
| "Dismiss" from the banner | Implemented | Persisted per-install dismissal |

The banner body text is `power_notice_body`; the long-form version is
`assets/legal/performance_notice.txt`. OS-imposed limits beyond Battery Saver
(memory pressure, OEM camera denial) surface through the live pipeline status
line instead of a second banner.

## Section 2 — Permission disclosure screens

| Requirement | Status | Where |
| --- | --- | --- |
| Camera rationale *before* the system prompt | Implemented | `showCameraRationale()` on the Grant button |
| Title, rationale and privacy notice copy | Implemented | `camera_rationale_title`, `camera_rationale_body` |
| Primary CTA "Grant Camera Permission" | Implemented | `camera_rationale_grant` |
| Secondary "Not Now" cancels without prompting | Implemented | `action_not_now` |
| Denied → settings fallback | Implemented | `showCameraSettingsFallback()` when the permission is permanently denied; links to `ACTION_APPLICATION_DETAILS_SETTINGS` |
| Notification rationale (Android 13+) | **Not applicable** | The app requests no notifications and runs no foreground service. Adding a camera foreground service would require this prompt plus a `POST_NOTIFICATIONS` request and a notification channel. |

## Section 3 — Verification

`LegalConsentSpec` asserts that:

- consent defaults to unaccepted on a fresh install;
- acceptance persists and does not itself enable capture;
- flipping the capture switch without accepting leaves the preference off;
- every legal document is bundled and readable, so a missing asset fails CI.

The disclaimer copy is plain text in `assets/legal/`, which makes it reviewable in
a diff and testable without a device.
