# Legal documents

The app's legal text is not duplicated here. It lives in
`app/src/main/assets/legal/` and is rendered verbatim, offline, by
[`LegalActivity`](../../app/src/main/java/com/edgevision/wallpaper/legal/LegalActivity.kt)
under **Settings → About & Legal**. One copy means the store listing, the in-app
text and the tests cannot drift apart.

| Asset | In-app title | Purpose |
| --- | --- | --- |
| `ai_disclosure.txt` | AI Detection Disclosure | Probabilistic-detection limits, model scope, on-device processing |
| `safety_notice.txt` | Safety & Emergency Notice | Not a life-safety device; no emergency calling |
| `performance_notice.txt` | Performance & Background Limits | Battery, heat, OS background and camera restrictions |
| `privacy_policy.txt` | Privacy Policy | Camera handling, capture storage, permissions, retention |
| `terms_and_conditions.txt` | Terms & Conditions | Licence, assistive-tool limits, lawful use, liability |

`LegalConsentSpec` asserts that every document is present in the APK and readable,
so a missing asset fails CI instead of shipping.

## Adaptation notice

The supplied source documents were written for a storage-cleaning utility. They
described microphone and sensor diagnostics, media scanning and automated file
deletion, none of which this app does, and they requested permissions this app
never asks for. Shipping that text would have made the Play Data Safety
declaration false.

The structure, section numbering, liability language, disclaimer copy and
permission-rationale flow were kept. Every factual claim was rewritten against
the code:

- camera frames are processed in RAM and never transmitted;
- capture is a user-enabled JPEG burst written through MediaStore;
- there is no microphone, sensor, location, contacts or media-scanning access;
- the app declares no network permission and bundles no analytics SDK.

## Before publishing

1. The support contact is `hakimafalul@gmail.com` and the postal address is
   "37/2, Pereira Lane, Colombo 6, Sri Lanka", in both `privacy_policy.txt` and
   `terms_and_conditions.txt`. Keep them in step with the Play Console listing if
   they change.
2. Host the privacy policy at a public URL (Play Console requires one). The asset
   text is the canonical copy; publish it unchanged.
3. Answer the Data Safety form as described in
   [play-console-justification.md](play-console-justification.md).
4. Keep the effective date in the assets in step with the version in
   `version.properties`.
