# Store listings: drafts for the owner (#26, #27)

Everything here is prepared for copy-paste; the owner submits it, because it needs the developer accounts. Check each
answer against the build you upload: the app's network behaviour is described in [PRIVACY.md](PRIVACY.md).

## Google Play: Data safety form

Google counts data as *collected* when the app sends it off the device, even to a service the user chose. Velum has
no server of its own, but three optional features send user content to services the user configures, so the
conservative answer is to declare them as collected, optional, and not shared.

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only; the webhook field rejects
`http://` except for localhost).
**Do you provide a way for users to request that their data is deleted?** Yes: the data is stored on the device;
Settings → Delete all data removes it. Data sent to a user-configured API, webhook, Slack or Notion is deleted in
those services.

| Data type | Collected | Shared | Optional | Purpose | Why |
|---|---|---|---|---|---|
| Audio → Voice or sound recordings | Yes | No | Yes | App functionality | Sent to the OpenAI-compatible transcription endpoint the user configures (only when API mode is chosen) |
| App activity → Other user-generated content (transcripts, notes) | Yes | No | Yes | App functionality | Sent to the user's AI endpoint for summaries / Ask, and to the webhook, Slack or Notion destination the user sets up |
| Personal info → Name (speaker names, if the user types them) | Yes | No | Yes | App functionality | Part of note content sent to the destinations above |

Not collected: location (the Android ≤ 11 location permission is only what the system requires for Bluetooth scans), contacts, calendar (read on the device only; "add to calendar" opens the calendar app),
device or other IDs (the recorder's Bluetooth identity stays on the device), app diagnostics, crash logs, analytics.

**Sharing:** none. Google does not treat a transfer the user starts to a service of their choice as sharing; Velum
never sends data to a party the user did not configure.

## Google Play: other declarations

- **Permissions** (with in-app rationale): microphone (recording), Bluetooth scan/connect (recorder sync), fine
  location only on Android 11 and older (`maxSdkVersion 30`, required there for Bluetooth scanning; no location is
  read or stored), calendar read (optional, names recordings), notifications, foreground service types `microphone`,
  `connectedDevice` and `dataSync`. No background location.
- **Content rating:** utility / productivity; no user-to-user communication, no user-generated content shared in the
  app, no ads. Expected rating: Everyone / PEGI 3.
- **Target audience:** 18+ (recording laws and consent), not designed for children.
- **Ads:** none. **In-app purchases:** none.
- **Privacy policy URL:** `https://github.com/bloodf/velum/blob/main/docs/PRIVACY.md` (or the website's copy once the
  domain is live).
- **Listing text, icon, feature graphic, screenshots:** `fastlane/metadata/android/{en-US,pt-BR}` (Fastlane `supply`
  layout).
- **Track:** internal testing first with the AAB from the Release workflow (`docs/RELEASING.md`).

## IzzyOnDroid request (after the first published GitHub Release)

Open an issue at <https://gitlab.com/IzzyOnDroid/repo/-/issues> with:

```text
App: Velum (dev.velum.app)
Source: https://github.com/bloodf/velum (MIT)
Releases: https://github.com/bloodf/velum/releases (signed APKs attached to each release)
APK to pick: velum-<version>-arm64-v8a.apk (per-ABI); velum-<version>-universal.apk also available
Signing certificate SHA-256: <value from SIGNING-CERT.txt of the release>
Metadata: fastlane/metadata/android (en-US, pt-BR)
Anti-features: none intended. No trackers, analytics or ads. Optional network use only to services the user
configures (OpenAI-compatible API, webhook, Slack, Notion) and model downloads. The APK has no dependency-metadata
signing block (dependenciesInfo.includeInApk = false).
```

## F-Droid (#27)

`metadata/dev.velum.app.yml` is the fdroiddata draft (builds the unsigned `-Pvelum.unsignedRelease=true` APK
from the tag, which F-Droid signs). Submit it as a merge request to <https://gitlab.com/fdroid/fdroiddata> after the first final tag;
the build server needs sherpa-onnx and LiteRT-LM from their published artifacts (see `THIRD_PARTY.md`).
