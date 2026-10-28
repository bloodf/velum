# Android 1.0 review — round 3

**Date:** 2026-10-28  
**Base reviewed:** `3a03300` on `main` (CI green: run `40670028151`)  
**Method:** Fresh skeptical review (no trust of round 1/2 claims) + JVM unit tests + new GitHub Actions **emulator device walk** (Compose UI + UI Automator, StrictMode penaltyDeath, pt-BR/EN × light/dark, debug + R8 release).

---

## Verdict

**Not ship-ready until the device walk is green on `main` and the findings below stay fixed.** Round 3 adds the runtime coverage previous rounds could not run in the agent VM, and found real data-loss / AutoFlow races that rounds 1–2 missed.

---

## Findings → fixes

| Sev | Finding | Location | Fix | SHA |
|-----|---------|----------|-----|-----|
| **high** | Recording ids `rec-{epoch}-{1000..9999}` + SQLDelight `INSERT OR REPLACE` → birthday collision silently destroys another recording | `AppContainer.createLocalRecording` / `persistDownloadedSession` | `RecordingIds.newId()` with 1e9–1e10 random; collision + overwrite regression tests | _(this push)_ |
| **high** | AutoFlow `awaitReadyStatus` accepted pre-enqueue `ready` → post-actions fired before WorkManager job started (wrong/stale template notes) | `AutoFlowRunner.runProcessing` / `awaitReadyStatus` | Force `queued` before enqueue; require in-flight before accepting `ready`; race test | _(this push)_ |
| **medium** | AutoFlow editor ids used the same 4-digit birthday space with `INSERT OR REPLACE` | `AutoFlowScreens.persist` | Wide 1e9–1e10 random | _(this push)_ |
| **info** | No instrumented full-shell walk in CI (prior rounds: emulator adb offline in agent VM) | `.github/workflows/ci.yml` | New `android-device-walk` job: KVM emulator, debug + release R8, screenshots + logcat artifacts | _(this push)_ |

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
| Bundled WAV import + fake local ASR/LLM transcription | yes (`DeviceWalkMode` engines) |
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
| Release (R8) build | yes (`connectedReleaseAndroidTest`) |
| Screenshots + logcat artifacts | yes (`velum-device-walk` artifact) |

**CI job:** `android-device-walk` (needs `jvm-android`). Script: `scripts/emulator-device-walk.sh`.

---

## Builds / CI

| Gate | Result |
|------|--------|
| Tip `3a03300` before round-3 changes | **green** — run `40670028151` |
| JVM unit tests (incl. new AutoFlow race + RecordingIds) | OK locally |
| Device walk on tip after this push | _watch next `main` run_ |

---

## 1.0-ready?

**Pending green device walk on `main` after this round.** Code findings above are fixed with tests; ship gate is the new emulator walk staying green (no crash/ANR/StrictMode/raw string) on debug + R8 release for all four locale/theme cells.
