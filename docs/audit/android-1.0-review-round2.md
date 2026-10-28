# Android 1.0 review — round 2

**Date:** 2026-10-28  
**Base reviewed:** `3c3c2b2` on `main` (after final-review fixes `e9ebb67` / `00db857`)  
**Method:** Fresh code review (no trust of prior audit claims) + JVM unit tests + `assembleDebug` / `assembleRelease` (R8) + 16 KB zipalign. Interactive emulator: `/dev/kvm` chmod’d and API 35 + API 30 ATD images tried with `-gpu swiftshader_indirect`; QEMU stayed **adb offline** (near-idle CPU) — same class of VM limitation as round 1. **CI `android-launch-smoke` remains the device-launch gate.**

---

## Verdict

**Conditionally 1.0-ready for the owner’s S26 Ultra + Note Pro** after the round-2 fixes on `main` land and CI stays green on the tip SHA.

Round 1 left real regressions and gaps (especially AppContainer hang-on-failure, summary version ID collisions, wipe leaving audio on disk, main-thread mic `runBlocking`, AutoFlow process-death seed). Those are fixed below. Remaining accepted residuals are SoftAP-not-wired (honest BLE copy), ungated `BleTransport.write` escape hatch (UI uses gated client), and peak RAM on huge BLE session decrypt (512 MiB hard cap).

---

## Findings → fixes

| Sev | Finding | Location | Fix | SHA |
|-----|---------|----------|-----|-----|
| **blocker** | `AppContainer` init exception left splash forever (`ready` never set / deferred never completed) | `VelumApplication`, `MainActivity` | `try/catch`, `completeExceptionally`, splash clears, fatal TextView (no raw stack) | `4321547` |
| **high** | `summary_version` ids `sv-{epoch}-{1000..9999}` + `INSERT OR REPLACE` → birthday collisions drop versions | `VelumRepository.newSummaryVersionId` | Recording-scoped id + 6-digit random; tests for 80-row backfill | `4321547` |
| **high** | v3→v4 backfill used `selectAllRecordings` (trashed=0) → trashed notes lost Versions | `backfillSummaryVersionsFromRecording` | Walk `selectAllRecordingIds` | `4321547` |
| **high** | Privacy wipe left WAV/Ogg on disk | `wipeAllUserData`, `StoragePrivacyScreen` | `deleteLocalFile` callback + `deleteFileAtPath` | `4321547` |
| **high** | Mic `runBlocking` / `Thread.sleep` on main (ANR) | `MicCapture.android`, Live pane, FAB | Suspend API; no `runBlocking`; UI uses coroutines | `4321547` |
| **high** | AutoFlow seed marked **all** ids processed → process death drops in-flight rematch | `AutoFlowRunner` | Seed skips in-flight statuses; claim id after work | `4321547` |
| **high** | `ensureApiKeyIfNeeded` ignored `ApiModelOverride` (wrong profile key) | `RecordingProcessor` | Pass override into resolve; merge template fields | `4321547` |
| **high** | Phone WAV still `readFileBytes` entire file | `RecordingProcessor` | Path-based header probe + sliced copy to spill | `4321547` |
| **medium** | Full-catalog meta=`1` before FTS rebuild → kill leaves All unsearchable | `ensureFullTemplatesImported` | Set meta **after** FTS rebuild | `4321547` |
| **medium** | `rebuildFtsIndex` left orphan FTS rows; `isEmpty` required both tables empty | `FtsIndex`, repo | `clearAll` before rebuild; `isEmpty` if either empty; transactional FTS upgrade | `4321547` |
| **medium** | Opus `RandomAccessFile.read` short reads; 20 ms PCM buffer; trailing continued packet dropped | `OpusDecoder.*`, demuxer | Read-fully loop; 120 ms frame buffer; EOS flush + test | `4321547` |
| **medium** | Failed mic start kept `status=recording` + navigated | `App.kt` FAB | Trash row on failure; navigate only on `Started` | `4321547` |
| **medium** | Detail tab not rotation-safe | `RecordingDetailScreen` | `rememberSaveable` for tab | `4321547` |
| **medium** | `versionName` still `0.1.0-foundation` | `composeApp/build.gradle.kts` | `1.0.0` / `versionCode 100` | `4321547` |
| **medium** | Prefer-Wi‑Fi settings hint claimed SoftAP downloads (BLE is the 1.0 path) | `Strings` preferWifi hints | Honest copy; status already said “BLE download active” | `4321547` |
| **low** | Device download still peaks on full decrypt ByteArray (512 MiB cap) | `DeviceViewModel` / `PlaudFileCrypto` | Accepted for 1.0; SoftAP+streaming decrypt deferred | — |
| **accepted** | `BleTransport.write` ungated | `KableBleTransport` | Production UI triple-gated via `PlaudDeviceClient` | — |
| **accepted** | SoftAP WS not WSS | wifi transfer | Hardware SoftAP parity; BLE used for 1.0 downloads | — |

### Hard rules re-checked (pass)

- Banned BLE opcodes `5, 104, 128, 0xFE20, 143` and settings `4, 26`: gated + tests.
- No Velum accounts/login/telemetry; no Plaud cloud client.
- API keys in Android Keystore; not logged.
- Manifest: only `MainActivity` exported; `allowBackup=false`; cleartext disabled.
- No Plaud product screenshots/branding; no secrets in tree.

### Builds

| Artifact | Result |
|----------|--------|
| JVM unit tests (`plaud-protocol`, `ble`, `core`, `ai`) | OK |
| `:composeApp:assembleDebug` | OK (~212 MB) |
| `:composeApp:assembleRelease` (R8) | OK (~191 MB) |
| Release `zipalign -P 16` | OK |
| Local interactive emulator walk | **Failed** (adb offline; QEMU idle) — use CI smoke |
| CI on fix tip `4321547` | **success** run `40669936045` |
| CI on SHA-pin `3a03300` | **success** run `40670028151` |

---

## 1.0-ready?

**Yes, for Android owner hardware**, once CI (including `assembleRelease` + emulator launch smoke) is green on the tip of `main` after this round-2 commit.

**Would block ship if still open:** infinite splash on init failure, version ID collisions, wipe leaving audio, main-thread mic `runBlocking`, AutoFlow process-death seed, API key preflight ignoring overrides.

**Does not block:** SoftAP security residuals, BLE download peak RAM under 512 MiB cap, iOS, website, OAuth, P2P, other BLE devices.
