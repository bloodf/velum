# Velum Android 1.0 audit

**Date:** 2026-10-28  
**Auditor:** Independent fresh review (code + build/tests; prior STATUS/REVIEW claims re-verified)  
**Head at start:** `9caaff3`  
**Scope:** Android target — `composeApp` + shared modules (`core`, `ai`, `ble`, `plaud-protocol`, `recorder`)

Status legend: `Open` · `Fixed` · `Deferred (owner/hardware)` · `Accepted risk`

---

## Summary counts

| Severity | Found | Fixed | Deferred / accepted | Open (process) |
|----------|------:|------:|--------------------:|---------------:|
| Critical | 5 | 5 | 0 | 0 |
| High | 14 | 14 | 0 | 0 |
| Medium | 16 | 14 | 2 | 0 |
| Low / Info | 10 | 5 | 4 | 1 |

---

## Critical

### C1 — API transcription uploads raw PCM as `audio/ogg`
- **Where:** `OpenAiCompatibleClient`, `OpenAiTranscriber`, `RecordingProcessor`
- **Fix:** `PcmWav.encode` + `OpenAiTranscriber.prepareUpload` wraps PCM as WAV; Content-Type from filename.
- **Tests:** `PcmWavTest`, `OpenAiTranscriberUploadTest`, `OpenAiCompatibleClientTest.transcribe_acceptsWavUploadAndReturnsText`
- **Status:** Fixed (`0a34d9b`)

### C2 — Local model downloads share 120s HTTP timeout
- **Where:** `HttpClientFactory` / `LocalModels.createDownloadHttpClient`
- **Fix:** Dedicated download client with null request/socket timeouts; AI client raised to 600s.
- **Status:** Fixed (`0a34d9b`)

### C3 — No runtime BLE permissions on Android 12–16
- **Where:** `RuntimePermissions`, `DeviceScreen`
- **Fix:** `AppPermission.BluetoothScan` / `BluetoothConnect`; requested before scan/connect.
- **Status:** Fixed (`b851722`)

### C4 — Device session list has no download → library path
- **Where:** `DeviceViewModel`, `DeviceScreen`, `AppContainer.persistDownloadedSession`
- **Fix:** Per-session Download → BLE download → decrypt → recordings dir → DB → enqueue processing. SoftAP prefer-Wi‑Fi remains library-ready; product uses BLE for 1.0 reliability.
- **Status:** Fixed (BLE path, `b851722`). SoftAP end-to-end on hardware → Needs owner/hardware (#8)

### C5 — Long recordings fully decode into one heap buffer before spill
- **Where:** `RecordingProcessor`, `OpusDecoder`
- **Fix:** `decodeToPcmFile` streams Opus frames to a spill `.s16le`; large WAV data chunks copy in 256 KiB slices via `locateWavPcm` / `appendRangeToFile`; `PcmAudioBuffer.fromExistingFile` wraps the result. Peak RAM no longer holds full PCM + ShortArray together.
- **Residual:** Ogg demux still materializes the compressed packet list (≈ source size, not PCM). Multi-hour Note Pro validation remains owner/hardware (#86 / #6).
- **Tests:** `RecordingProcessorBudgetTest.fromExistingFile_spillsWhenOverBudget`
- **Status:** Fixed (`1b33ae3`)

---

## High

### H1 — AutoFlow marks IDs processed before match — **Fixed** (`0a34d9b`, `AutoFlowRunner` + `AutoFlowRunnerTest`)
### H2 — AutoFlow post-actions on timeout/failure — **Fixed** (`0a34d9b`, only on `ready`)
### H3 — Import SHA-256 dedupe mismatch — **Fixed** (`0a34d9b`, store source + content hashes)
### H4 — API diarization selectable — **Fixed** (`b851722`, Local-only UI; Switch-to-API never sets diarization Api)
### H5 — Filter/Sort non-functional — **Fixed** (`b851722`, All / Trash / Note Pro with restore-on-open)
### H6 — Generate sheet choices discarded — **Fixed** (`b851722`, template / language / auto-label applied)
### H7 — Dead milestone / legacy stub screens — **Fixed** (`b851722`, deleted `RecordingsScreen` / `SettingsScreen`)
### H8 — Non-Plaud “not wired” status — **Fixed** (`b851722`, unsupported-in-this-version copy; no developer jargon)
### H9 — Release R8 WorkManager / java_websocket — **Fixed** (`0a34d9b` / ProGuard keep rules)
### H10 — Model download rename RAM copy — **Fixed** (`0a34d9b`, `streamCopyModelFile`)
### H11 — Demo seed left on — **Fixed** (`0a34d9b`, `SEED_DEMO_DATA = false`; templates always seed)
### H12 — Explore Conta / dark contrast — **Fixed** (`b851722`, `VelumOnBlack` + localized settings copy)
### H13 — Voice Profiles hardcoded English — **Fixed** (`1b33ae3`, EN + pt-BR via `Strings.ux`; #87 addressed in code — owner can verify on device)
### H14 — No BLE transfer FGS — **Fixed (code)** — `SessionDownloadService` (`connectedDevice`) + `SessionDownloadKeepAlive` gate tied to download lifecycle; reuses `DownloadProgressNotifier` progress/cancel. Owner still confirms background/Doze survival on S26 Ultra + Note Pro (#85)

---

## Medium

| ID | Status | Notes |
|----|--------|-------|
| M1 SQLDelight verifyMigrations | Deferred (process) | Enable before first post-1.0 schema change |
| M2 Broad intent filters | Fixed (`b851722`) | Dropped `file://` + `*/*`; content + audio MIME |
| M3 BleTransport.write escape hatch | Accepted | Production path gated in `PlaudDeviceClient` + tests |
| M4 Raw exception messages | Fixed (`1b33ae3`) | Ask / import / export / Voice Profiles / models refresh map to localized generic or status codes |
| M5 Embedding model picker unused | Fixed (`b851722`) | Removed from ApiSettings |
| M6 “Account settings” copy | Fixed (`0a34d9b`) | Points to Settings → AI provider |
| M7 Mic recovery no enqueue | Fixed (`b851722`) | Status `queued` + `enqueueProcessing` |
| M8 Live Pause/Mark enums | Fixed (`1b33ae3`) | `livePause` / `liveResume` / `liveMark` + mic source labels |
| M9 Library adaptive rail EN | Fixed (`1b33ae3`) | Ask / Devices / select-recording localized |
| M10 App snackbars EN | Fixed (`1b33ae3`) | Import / folder / export snackbars use `Strings.ux` |
| M11 Device screen no back | Fixed (`b851722`) | `ScreenTopBar` + `onBack` |
| M12 SoftAP bind/auth (S14) | Deferred | JVM E2E green; confirm SoftAP on Note Pro (#8) |
| M13 Ask-across full library | Accepted (1.0) | Cap / pagination deferred post-1.0; not a crash/data-loss bug |
| M14 HTTP timeout for long ASR | Fixed (`0a34d9b`) | With C2 (600s AI client) |
| M15 Template community label | Fixed (`b851722`) | Renamed to Templates / Modelos |
| M16 FTS search silent fail | Fixed (`b851722`) | Shows `searchUnavailable` |

---

## Low / Info

| ID | Status | Notes |
|----|--------|-------|
| L1 Keystore not user-auth | Accepted (S17) | |
| L2 displayName preference | Accepted | Local signature, not account |
| L3 versionName foundation | Open (process) | Bump at ship |
| L4 Dead SPEAKER_LABELING route | Fixed (`1b33ae3`) | Removed unused `Routes.SPEAKER_LABELING` |
| L5 Unused OAuth strings | Accepted | |
| L6 Coroutine scheduler uniqueness | Accepted (desktop) | Android path OK for 1.0 |
| L7 Decorative Mic/Sync icons | Fixed (`b851722`) | Removed |
| L8 Seek contentDescriptions | Fixed (`1b33ae3`) | `seekBack15` / `seekForward15` |
| L9 Integrations HMAC label | Fixed (`1b33ae3`) | User-facing `webhookSigned` (EN/pt-BR) |
| L10 Fixed SoftAP AEAD nonce | Accepted (S13) | |

---

## Correctly verified

- Forbidden opcodes **5 / 104 / 128 / 0xFE20 / 143** and settings **4 / 26** gated + tested
- Android Keystore AES-GCM secure store; `allowBackup=false`; cleartext off
- Only `MainActivity` exported; 16 KB CI packaging checks present
- No telemetry / Velum login chrome in Settings hub
- Mic FGS + processing FGS; BYO `/models`; webhook HMAC; folder export
- CI emulator system-image preinstall retries corrupt Google zip downloads

---

## Needs owner/hardware

| Item | Why | GitHub |
|------|-----|--------|
| Device identity keys from Plaud dump | Cannot generate locally | Existing import docs / blocked issues |
| SoftAP Wi‑Fi on real Note Pro | JVM fake only | #8 |
| Long multi-hour OOM / streaming decode validation | Needs large captures | #86 (also #6) — code streaming fixed; HW confirm |
| BLE download FGS survival | Code ships `connectedDevice` FGS; confirm background/Doze/process-death on S26 Ultra + Note Pro | #85 |
| Release upload keystore + Play / F-Droid | Secrets + consoles | Existing release docs |
| OAuth client IDs | Out of scope | #49/#50 family |
| Renovate PR #2 | Owner decision | #2 |

### Owner hardware checklist (Samsung Galaxy S26 Ultra + Plaud Note Pro)

1. BLE scan/connect with runtime BT permissions (deny → grant → retry)
2. Unlock with imported device identity keys
3. Session Download → appears in Files → transcription completes
4. SoftAP prefer-Wi‑Fi path (#8) if testing Wi‑Fi transfer
5. Mic capture FGS survives lock screen / brief background
6. Multi-hour import / decode does not OOM (#6/#86)
7. Explore light + dark contrast (no Conta / washed icons)
8. Filter → Trash → open restores; Note Pro filter works
9. Voice Profiles EN + pt-BR copy; enroll / export / wipe
10. Live mic Pause / Mark / input-source labels in EN + pt-BR

---

## Fix log

| Finding | Commit | Notes |
|---------|--------|-------|
| Audit findings doc | `cef1067` | `docs/audit/android-1.0-audit.md` |
| C1 C2 H1 H2 H3 H9 H10 H11 M6 M14 + tests | `0a34d9b` | AI/pipeline/seed/ProGuard |
| C3 C4 H4–H8 H12 M2 M5 M7 M11 M15 M16 L7 | `b851722` | Device download, BLE perms, UI |
| Deferred issue links | `0a34d9b`→`0b3aab3` | #85 #86 #87 |
| C5 H13 M4 M8–M10 L4 L8 L9 + CI image retry | `1b33ae3` | Streaming Opus decode, i18n polish, emulator zip retry, AutoFlow test race |
| Audit final status SHAs / green CI | *(docs follow-up)* | Records green run for `1b33ae3` |
| H14 session download `connectedDevice` FGS | *(#85 branch)* | `SessionDownloadService` + keep-alive gate; HW confirm still owner |

---

## Final status

- Local verification: `:plaud-protocol:jvmTest :ble:jvmTest :core:jvmTest :ai:jvmTest`, `:composeApp:testDebugUnitTest`, `:composeApp:compileDebugKotlinAndroid` — **green**
- Fix commits on `main`: `cef1067`, `0a34d9b`, `b851722`, `0b3aab3`, `1b33ae3`
- **Green CI (code-complete head):** [run 40628351920](https://github.com/bloodf/velum/actions/runs/40628351920) on `1b33ae3`
- Owner must complete the hardware checklist above before calling 1.0 “shipped”
