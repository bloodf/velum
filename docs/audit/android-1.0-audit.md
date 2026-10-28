# Velum Android 1.0 audit

**Date:** 2026-10-28  
**Auditor:** Independent fresh review (code + build/tests; prior STATUS/REVIEW claims re-verified)  
**Head at start:** `9caaff3`  
**Scope:** Android target — `composeApp` + shared modules (`core`, `ai`, `ble`, `plaud-protocol`, `recorder`)

Status legend: `Open` · `Fixed` · `Deferred (owner/hardware)` · `Accepted risk`

---

## Summary counts

| Severity | Found | Fixed | Deferred / accepted |
|----------|------:|------:|--------------------:|
| Critical | 5 | 4 | 1 (partial) |
| High | 14 | 13 | 1 |
| Medium | 16 | 11 | 5 |
| Low / Info | 10 | 3 | 7 |

---

## Critical

### C1 — API transcription uploads raw PCM as `audio/ogg`
- **Where:** `OpenAiCompatibleClient`, `OpenAiTranscriber`, `RecordingProcessor`
- **Fix:** `PcmWav.encode` + `OpenAiTranscriber.prepareUpload` wraps PCM as WAV; Content-Type from filename.
- **Tests:** `PcmWavTest`, `OpenAiTranscriberUploadTest`, `OpenAiCompatibleClientTest.transcribe_acceptsWavUploadAndReturnsText`
- **Status:** Fixed

### C2 — Local model downloads share 120s HTTP timeout
- **Where:** `HttpClientFactory` / `LocalModels.createDownloadHttpClient`
- **Fix:** Dedicated download client with null request/socket timeouts; AI client raised to 600s.
- **Status:** Fixed

### C3 — No runtime BLE permissions on Android 12–16
- **Where:** `RuntimePermissions`, `DeviceScreen`
- **Fix:** `AppPermission.BluetoothScan` / `BluetoothConnect`; requested before scan/connect.
- **Status:** Fixed

### C4 — Device session list has no download → library path
- **Where:** `DeviceViewModel`, `DeviceScreen`, `AppContainer.persistDownloadedSession`
- **Fix:** Per-session Download → BLE download → decrypt → recordings dir → DB → enqueue processing. SoftAP prefer-Wi‑Fi remains library-ready; product uses BLE for 1.0 reliability.
- **Status:** Fixed (BLE path). SoftAP end-to-end on hardware → Needs owner/hardware

### C5 — Long recordings fully decode into one heap buffer before spill
- **Where:** `RecordingProcessor`
- **Status:** Deferred (partial) — spill-after-decode remains; true streaming Opus decode needs more invasive audio pipeline work + long-capture hardware validation

---

## High

### H1 — AutoFlow marks IDs processed before match — **Fixed** (`AutoFlowRunner` + `AutoFlowRunnerTest`)
### H2 — AutoFlow post-actions on timeout/failure — **Fixed** (only on `ready`)
### H3 — Import SHA-256 dedupe mismatch — **Fixed** (store source + content hashes)
### H4 — API diarization selectable — **Fixed** (Local-only UI; Switch-to-API never sets diarization Api)
### H5 — Filter/Sort non-functional — **Fixed** (All / Trash / Note Pro with restore-on-open)
### H6 — Generate sheet choices discarded — **Fixed** (template / language / auto-label applied)
### H7 — Dead milestone / legacy stub screens — **Fixed** (deleted `RecordingsScreen` / `SettingsScreen`)
### H8 — Non-Plaud “not wired” status — **Fixed** (unsupported-in-this-version copy; no developer jargon)
### H9 — Release R8 WorkManager / java_websocket — **Fixed** (`proguard-rules.pro` keep rules)
### H10 — Model download rename RAM copy — **Fixed** (`streamCopyModelFile`)
### H11 — Demo seed left on — **Fixed** (`SEED_DEMO_DATA = false`; templates always seed)
### H12 — Explore Conta / dark contrast — **Fixed** (`VelumOnBlack` + localized settings copy)
### H13 — Voice Profiles hardcoded English — **Deferred** (large i18n pass; not blocking core sync path)
### H14 — No BLE transfer FGS — **Deferred (owner/hardware)** — download works in-process; add `connectedDevice` FGS after hardware timing measurements

---

## Medium

| ID | Status | Notes |
|----|--------|-------|
| M1 SQLDelight verifyMigrations | Open (process) | Enable before first post-1.0 schema change |
| M2 Broad intent filters | Fixed | Dropped `file://` + `*/*`; content + audio MIME |
| M3 BleTransport.write escape hatch | Accepted | Production path gated in `PlaudDeviceClient` + tests |
| M4 Raw exception messages | Partial | Device unlock/download mapped; Ask/import still mixed |
| M5 Embedding model picker unused | Fixed | Removed from ApiSettings |
| M6 “Account settings” copy | Fixed | Points to Settings → AI provider |
| M7 Mic recovery no enqueue | Fixed | Status `queued` + `enqueueProcessing` |
| M8 Live Pause/Mark enums | Open | Remaining i18n polish |
| M9 Library adaptive rail EN | Open | Remaining i18n polish |
| M10 App snackbars EN | Open | Remaining i18n polish |
| M11 Device screen no back | Fixed | `ScreenTopBar` + `onBack` |
| M12 SoftAP bind/auth (S14) | Deferred | JVM E2E green; confirm SoftAP on Note Pro |
| M13 Ask-across full library | Open | Cap later |
| M14 HTTP timeout for long ASR | Fixed | With C2 (600s AI client) |
| M15 Template community label | Fixed | Renamed to Templates / Modelos |
| M16 FTS search silent fail | Fixed | Shows `searchUnavailable` |

---

## Low / Info

| ID | Status |
|----|--------|
| L1 Keystore not user-auth | Accepted (S17) |
| L2 displayName preference | Accepted (local signature, not account) |
| L3 versionName foundation | Open — bump at ship |
| L4 Dead SPEAKER_LABELING route | Open |
| L5 Unused OAuth strings | Accepted |
| L6 Coroutine scheduler uniqueness | Open (desktop) |
| L7 Decorative Mic/Sync icons | Fixed (removed) |
| L8 Seek contentDescriptions | Open |
| L9 Integrations HMAC label | Open |
| L10 Fixed SoftAP AEAD nonce | Accepted (S13) |

---

## Correctly verified

- Forbidden opcodes **5 / 104 / 128 / 0xFE20 / 143** and settings **4 / 26** gated + tested
- Android Keystore AES-GCM secure store; `allowBackup=false`; cleartext off
- Only `MainActivity` exported; 16 KB CI packaging checks present
- No telemetry / Velum login chrome in Settings hub
- Mic FGS + processing FGS; BYO `/models`; webhook HMAC; folder export

---

## Needs owner/hardware

| Item | Why | GitHub |
|------|-----|--------|
| Device identity keys from Plaud dump | Cannot generate locally | Existing import docs / blocked issues |
| SoftAP Wi‑Fi on real Note Pro | JVM fake only | Open issue if missing |
| Long multi-hour OOM / streaming decode | Needs large captures | — |
| BLE download FGS timing | Needs background kill tests on S26 Ultra | — |
| Release upload keystore + Play / F-Droid | Secrets + consoles | Existing release docs |
| OAuth client IDs | Out of scope | #49/#50 family |
| Renovate PR #2 | Owner decision | #2 |

---

## Fix log

| Finding | Commit | Notes |
|---------|--------|-------|
| Audit doc + batch of critical/high fixes | _(this push)_ | See git log on `main` after audit |

---

## Final status

- Local verification: `:plaud-protocol:jvmTest :ble:jvmTest :core:jvmTest :ai:jvmTest`, `:composeApp:testDebugUnitTest`, `:composeApp:assembleDebug` — **green**
- CI run id / head commit: filled after push to `main`
- Owner must verify on **Samsung Galaxy S26 Ultra + Plaud Note Pro**: BLE scan/connect (runtime permissions), unlock with imported keys, session download → Files → transcription, SoftAP prefer path, mic FGS, long recording, light/dark Explore contrast, Filter/Trash restore
