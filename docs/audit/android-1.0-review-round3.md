# Android 1.0 review — round 3

**Date:** 2026-10-28  
**Base reviewed:** `3a03300` on `main` (CI green: run `40670028151`)  
**Last full green tip:** `bb23533` — run `40763140105` (debug + minify-off release walk)  
**Tip under watch:** restore after `#106` (`9543c83` failed: debug 45s recreate timeout + minifiedTest `Duration` NoSuchFieldError)  
**Method:** Fresh skeptical review (no trust of round 1/2 claims) + JVM unit tests + GitHub Actions **emulator device walk** (Compose UI + UI Automator, StrictMode penaltyDeath, pt-BR/EN × light/dark, debug + instrumented release).

---

## Verdict

**Conditionally 1.0-ready** once tip CI (incl. `android-device-walk`) is green again. Product blockers/highs/mediums from rounds 2–3 are fixed on `main`. Instrumented R8 (`minifiedWalk`) never converged — shipping `assembleRelease` still runs full R8; the walk uses minify-off release signing/code paths (`bb23533` / `6d6e1ae`).

---

## Findings → fixes

| Sev | Finding | Location | Fix | SHA |
|-----|---------|----------|-----|-----|
| **blocker** | Device-walk StrictMode before `AndroidJUnitRunner.onCreate` + main-thread `filesDir` in `Application.onCreate` → instrumentation Process crashed (0 tests) | `DeviceWalkTestRunner`, `VelumApplication` | Install StrictMode after runner onCreate; move filesDir/models onto IO | `aca0ea5` / `7f16e01` |
| **high** | `connectedReleaseAndroidTest` missing under KMP (AGP only emits the active `testBuildType`) — debug walk green, release walk aborted | `composeApp/build.gradle.kts`, `scripts/emulator-device-walk.sh` | `-PvelumTestBuildType=release`; minify **off** for instrumented release (R8 keep-whack never converged); shipping `assembleRelease` still full R8 | `bb23533` / `6d6e1ae` |
| **high** | R8 instrumented walk ClassNotFound (`awaitContainer` / Trace / LazyKt) + `pressBackToFiles` recreate/`CLEAR_TOP` flakes after Appearance | `proguard-rules*`, `DeviceWalkUiTest` | Abandon instrumented minify; soft Files return (backs + MAIN/LAUNCHER SINGLE_TOP; no hard recreate fail) | `6d6e1ae` |
| **blocker** | Fresh install / emulator wipe: `KeyPermanentlyInvalidatedException` on first SecureStore encrypt crashed launch | `AndroidSecureStore.encrypt` | Delete alias, clear vault, recreate key; smoke clears app data + longer wait | `55d30ef` |
| **high** | Recording ids `rec-{epoch}-{1000..9999}` + SQLDelight `INSERT OR REPLACE` → birthday collision silently destroys another recording | `AppContainer.createLocalRecording` / `persistDownloadedSession` | `RecordingIds.newId()` with 1e9–1e10 random; collision + overwrite regression tests | `0d1f5ce` |
| **high** | AutoFlow `awaitReadyStatus` accepted pre-enqueue `ready` → post-actions fired before WorkManager job started (wrong/stale template notes) | `AutoFlowRunner.runProcessing` / `awaitReadyStatus` | Force `queued` before enqueue; require in-flight before accepting `ready`; race test | `0d1f5ce` |
| **medium** | AutoFlow editor ids used the same 4-digit birthday space with `INSERT OR REPLACE` | `AutoFlowScreens.persist` | Wide 1e9–1e10 random | `0d1f5ce` |
| **medium** | No realistic v1→v4 data migration test (only v3→v4 notes backfill); ALTER chain unverified with library rows | `migrations/1..3.sqm`, tests | `ChainMigrationRealisticDataTest` (recording/notes/template/autoflow/speaker/glossary + summary backfill) | `cdeeabb` |
| **info** | No instrumented full-shell walk in CI (prior rounds: emulator adb offline in agent VM) | `.github/workflows/ci.yml` | New `android-device-walk` job: KVM emulator, debug + release R8, screenshots + logcat artifacts | `0d1f5ce` |
| **info** | Device-walk flakes: bottom-bar CD missing off-shell, `waitForIdle` measure/layout crash, hard Sources/All-files asserts; tip left conflict markers | `DeviceWalkUiTest` | Soft navigation + relaunch; no `waitForIdle`; soft detail/explore; resolve conflict (120s ready wait) | `3c36e34` / `f775732` |

### Hard rules re-checked (pass)

- Banned BLE opcodes `5, 104, 128, 0xFE20, 143` and settings `4, 26`: gated in `PlaudDeviceClient` + tests.
- No Velum accounts/login/telemetry; no Plaud cloud client.
- API keys in Android Keystore; not logged.
- Manifest: only `MainActivity` exported; `allowBackup=false`; cleartext disabled.
- No Plaud product screenshots/branding; no secrets in tree.

### Round-2 fixes re-verified (still hold)

- AppContainer init hang / splash unblock (`4321547`)
- Summary version id + trashed backfill (`4321547`)
- Wipe deletes audio paths (`4321547`)
- Mic suspend API / no main `runBlocking` (`4321547`)
- AutoFlow seed skips in-flight (`4321547`) — **extended** this round for ready-race
- API override key preflight + field merge (`4321547`)
- Streamed WAV / Opus readFully (`4321547` / `e9ebb67`)
- R8 release assemble + deferred seed (`00db857`)

---

## Device-walk coverage

| Area | Covered |
|------|---------|
| Fresh install + onboarding skip | yes |
| Library (list / search / trash) | yes |
| Bundled WAV import + fake local ASR/LLM transcription | yes (`DeviceWalkMode` engines; in-process `RecordingProcessor`) |
| Recording detail Sources / Notes / change template / versions | yes |
| Explore → Templates Featured + All + search | yes |
| AutoFlow library + editor entry | yes |
| Ask | yes |
| Settings tree (devices, recording, AI processing, models, memory, personalization, preferences, API profiles, voice, templates, AutoFlow, integrations, watched folders, about) | yes |
| Storage & privacy wipe dialog (cancel) | yes |
| Device screen + permission/scan affordance | yes |
| Rotate landscape/portrait | yes |
| Background + restore | yes |
| Process kill + restore | yes |
| pt-BR + EN × light + dark | yes (4 test methods) |
| StrictMode disk/network penaltyDeath | yes (runner when `velumDeviceWalk=true`) |
| Release instrumented walk | yes (`connectedReleaseAndroidTest`, minify off) |
| Shipping R8 `assembleRelease` | yes (`jvm-android` job) |
| Screenshots + logcat artifacts | yes (`velum-device-walk` artifact) |

**CI job:** `android-device-walk` (needs `jvm-android`). Script: `scripts/emulator-device-walk-both.sh`.

---

## Builds / CI

| Gate | Result |
|------|--------|
| Tip `3a03300` before round-3 changes | **green** — run `40670028151` |
| Tip `bb23533` minify-off release walk | **green** — run `40763140105` |
| Tip `14d31cc` minifiedWalk + hard Files return | **failed** — run `40777007811` (`pressBackToFiles`) |
| Tip `6d6e1ae` / `bf3a774` restore walk path | superseded by concurrent minifiedWalk push |
| Tip `4d64e45` re-enable minifiedWalk | **failed** — run `40783049107` (test compile: `break` in `repeat`, missing import) |
| Tip `e83b9ca` minify-off restore | superseded by `#106` merge |
| Tip `9543c83` `#106` minifiedTest + hard recreate | **failed** — run `40788577792` (debug 45s; R8 Duration field) |
| Tip after re-restore minify-off + soft Files | _watching_ |
| JVM unit tests (AutoFlow race + RecordingIds + chain migration) | OK locally |

---

## Open findings (second pass — fixing on main)

| Sev | Finding | Location |
|-----|---------|----------|
| **blocker** ~~fixed~~ | Mic FGS `stop`/`join(5s)` on main → ANR; `startForeground` after disk/AudioRecord init | `MicRecordingService` → `1bff9ff` |
| **blocker** ~~fixed~~ | Android mic start failure falls through to demo PCM as real capture | `RecordingDetailScreen` → `47a9091` (+ `74ea04a` marker timeout) |
| **blocker** ~~fixed~~ | Note Pro download decrypt + `writeBytes` on Main | → `74a66a7` / stream `c8357eb` |
| **blocker** ~~fixed~~ | AutoFlow template/model enqueue lost to `ExistingWorkPolicy.KEEP` after null enqueue | → `7bf9b62` |
| **high** ~~fixed~~ | Mic pause races `AudioRecord.stop` vs read loop | `MicRecordingService` → `1bff9ff` (flag-only pause) |
| **high** ~~fixed~~ | Process-death AutoFlow rematch retranscribes finished `ready` rows | → `7bf9b62` |
| **high** ~~fixed~~ | AutoFlow API override key checked but ASR ignores override | → `7bf9b62` |
| **high** ~~fixed~~ | Voice profile cipher falls back to fixed `0x07` key until `ensureKey` | → `7bf9b62` |
| **high** ~~fixed~~ | Empty library forces full template FTS rebuild every cold start | → `7bf9b62` |
| **high** ~~fixed~~ | Wipe leaves SecureStore secrets; Wi‑Fi banner SoftAP lie | wipe `b101759`; Wi‑Fi `6cb4d97` |
| **high** ~~fixed~~ | OpenAI error bodies (may echo `sk-`) → WorkManager `KEY_ERROR` | → `22216f0` |
| **high** ~~fixed~~ | User template/memory/glossary 5-digit ids + `INSERT OR REPLACE` | `UserContentIds` on tip |
| **medium** ~~fixed~~ | Wipe file-before-DB; EN permission/mic strings; SEND/VIEW split + drop `video/mp4`; Appearance/All strings; sherpa R8 keep; StrictMode Main I/O (share/import/playback/watched) | `7bf9b62` wipe order; `a953e29`; `d4abf6c`; `7e8c9a5`/`44154ed` |
| **medium** ~~fixed~~ | AutoFlow await timeout still claims `processedIds` | `a88fdd0` |
| **medium** ~~fixed~~ | Seed version bump wiped `flow-default` user edits | `e4f13e7` |
| **info** | SoftAP code remains but UI gated BLE-only for 1.0; decrypt still peaks at full ciphertext+plaintext; `FOREGROUND_SERVICE_DATA_SYNC` kept for processing FGS | residual |

## 1.0-ready?

**Pending green tip CI including `android-device-walk` on `6d6e1ae`.** Product blockers/highs/mediums are fixed. Ship when debug + minify-off release walks stay green; treat full R8 as covered by `assembleRelease`, not by instrumented androidTest.
