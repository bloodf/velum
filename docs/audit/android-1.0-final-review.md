# Android 1.0 final review (skeptical re-pass)

**Date:** 2026-10-28  
**Head reviewed:** `00db857` on `main` (prior fix commit `e9ebb67`)  
**Target:** Samsung Galaxy S26 Ultra + Plaud Note Pro  
**Method:** Code + JVM tests + `assembleRelease` (R8) + Roborazzi light/dark EN/pt-BR. Interactive emulator in this VM hit KVM permission / System UI ANR under software accel; CI emulator smoke is the authoritative device-launch signal.

Prior docs (`android-1.0-audit.md`, `android-1.0-ux-review.md`, `templates-hygiene.md`) were **not trusted** — every claim below was re-verified from code/tests/build.

---

## Verdict

**Conditionally 1.0-ready for the owner’s S26 Ultra + Note Pro** after the fixes in this pass land and CI stays green on the final head SHA.

Remaining non-blockers for *this* ship (accepted / out of scope): SoftAP WS `:8081` not WSS (hardware SoftAP parity), ungated `BleTransport.write` escape hatch (production UI only uses `PlaudDeviceClient`), iOS/desktop stubs, OAuth/P2P/desktop.

---

## Findings (verified) → fixes

| Sev | Finding | Location | Fix | SHA |
|-----|---------|----------|-----|-----|
| **blocker** | `assembleRelease` / R8 failed: missing optional zstd + JVM `ManagementFactory` | `composeApp/proguard-rules.pro` | `-dontwarn` for commons-compress zstd + Ktor debug detector; CI now runs `assembleRelease` | `00db857` |
| **high** | First launch ANR risk: Featured ~1500 template seed + FTS on **main thread** in `Application.onCreate` | `VelumApplication`, `AppContainer` → `VelumRepository` init | Build `AppContainer` on `Dispatchers.IO`; splash `setKeepOnScreenCondition` until ready | `00db857` |
| **high** | `template_fts` **DROP** on every cold start → full FTS rebuild (jank after All import) | `FtsIndex.ensureSchema` | Recreate only when tags column missing; rebuild only when needed | `e9ebb67` |
| **high** | Wipe deleted AutoFlows but left seed marker → **no default AutoFlow** after wipe | `VelumRepository.wipeAllUserData`, `SeedData` | `clearSeedMarker` + reseed; tests | `e9ebb67` |
| **high** | Schema v3→v4 created empty `summary_version`; existing notes invisible in Versions | `migrations/3.sqm`, repo init | Backfill active version from `summary_markdown`; Versions sheet calls `ensureBaselineVersion` | `e9ebb67` |
| **high** | Full catalog import gunzipped ~29MB into one `String` | `PlaudFullTemplateAsset`, `ensureFullTemplatesImported` | Line-by-line `forEachLine` + `parseJsonlLine` | `e9ebb67` |
| **high** | “Streaming” Opus still held full Ogg + all packets in RAM | `OggOpusDemuxer`, `RecordingProcessor`, platform decoders | `demuxForEach` / `demuxAtForEach` + `decodePathToPcmFile` from disk | `e9ebb67` |
| **high** | Notes tab showed forever **Generating** when summary blank (even `ready`) | `RecordingDetailScreen` NotesPane | Split generating vs empty CTA | `e9ebb67` |
| **medium** | Ask AI / import showed raw exception text | Detail / ImportHub | Localized `genericError` | `e9ebb67` |
| **medium** | Hardcoded EN: template import status, `" min"`, prefs language codes, ranking badge | Templates / Files / Preferences | New `Strings` keys + labels | `e9ebb67` |
| **medium** | Gradle race: `validateAutoflowTemplates` vs `processDebugJavaRes` | `core/build.gradle.kts` | Depend on `*JavaRes` tasks | `00db857` |
| **low** | Template back `contentDescription` missing | `TemplatesScreen` | a11y back label | `e9ebb67` |
| **low** | Templates grid no empty state | `TemplatesScreen` | `EmptyState` when filtered empty | `e9ebb67` |
| **accepted** | `BleTransport.write` ungated | `KableBleTransport` | Production path triple-gated; leave as M3 residual | — |
| **accepted** | SoftAP WS not WSS / fixed nonce | wifi transfer | Hardware SoftAP parity; BLE fallback | — |
| **oos** | iOS All catalog / Opus / PDF stubs | iosMain | Android-first 1.0 | — |

### Hard rules re-checked (pass)

- Banned BLE opcodes `5, 104, 128, 0xFE20, 143` and settings writes `4, 26`: gated in Framing / Unlock / `PlaudDeviceClient.assertOutboundAllowed` + tests.
- No Velum accounts/login/telemetry; no Plaud cloud client.
- API keys in Android Keystore (`AndroidSecureStore`); not logged.
- Manifest: only `MainActivity` exported; `allowBackup=false`; cleartext disabled.
- No Plaud product screenshots/branding committed; no secrets in tree.

### Feature re-checks

| Area | Result |
|------|--------|
| AutoFlow 107 templates | All `template_id` resolve to real note templates (CI `validateAutoflowTemplates`) |
| Featured 1500 / All ~16k | Assets real; lazy All import streamed; FTS+tags/filters wired |
| Summary versions (v4) | Migration dumps + `verifyMigrations`; backfill tested (`SummaryVersionMigrationTest`) |
| API model profiles | Resolver + Generate/AutoFlow overrides present |
| PDF export | Android yes |
| Local model downloads | HTTPS + SHA-256 gated |
| 16 KB ELF align | Release APK: 16 libs checked, `zipalign -P 16` OK |
| APK size | Debug ~212 MB, release ~191 MB (native ASR/LLM) — large but expected |

---

## Tests added/updated

- `FtsSchemaReuseTest` — second `ensureSchema` must not drop FTS  
- `WipeReseedTest` / `WipeAndExportTest` — default AutoFlow after wipe  
- `SummaryVersionMigrationTest` — real v3 data → v4, notes preserved + version backfilled  
- `OggOpusDemuxerTest.demuxForEach_invokes_onHeader…`  
- Roborazzi EN light/dark home/settings/summary captures  

---

## Builds & CI

| Artifact | Result |
|----------|--------|
| `:composeApp:assembleDebug` | OK |
| `:composeApp:assembleRelease` (R8 minify) | OK after ProGuard dontwarn |
| Release 16 KB check | OK (`fail=0`, 16×64-bit `.so`) |
| JVM unit tests | OK |
| Roborazzi record | OK (pt-BR light/dark + EN matrix) |
| CI on `e9ebb67` | **success** run `40656553741` |
| CI on `00db857` | **success** run `40661583286` (includes `assembleRelease` + emulator smoke) |
| CI on tip `8c8a6e7` | **success** run `40661637876` |

### Emulator note

Local API 35 emulator without usable KVM produced System UI ANR during first interactive pass. After chmod on `/dev/kvm`, boot still raced with adb. **Do not treat local ANR dialogs as app defects** without KVM. CI `android-launch-smoke` on GitHub runners (KVM + `scripts/emulator-launch-smoke.sh`) is the launch gate.

Screenshots: `docs/audit/final/` + `composeApp/build/outputs/roborazzi/` (pt-BR default suite + `velum-en-*`).

---

## Owner checklist — S26 Ultra + Note Pro

1. Install **release** (or Play internal) build from green CI head; confirm splash → library without ANR.  
2. **Language:** Settings → Appearance → English and Português (Brasil); spot-check Library, Notes empty CTA, Templates, Settings.  
3. **Theme:** light + dark.  
4. **Note Pro:** pair once; import device keys if needed; download one short session; confirm decrypt → library row.  
5. **Never** expect OTA / auto-delete / auto-sync device settings changes from Velum (banned).  
6. Open **Templates → Discover → All** once on Wi‑Fi/charging; wait for import; search by tag.  
7. Generate notes on a ready recording; open **Versions**; regenerate; confirm prior version kept.  
8. Optional: configure API profile + per-task model; run Generate with override.  
9. Export PDF from a note; share to Files.  
10. Wipe all data once in a throwaway install; confirm default AutoFlow returns.  
11. Download recommended local models only if using Local compute; otherwise leave API.  
12. No account/login expected — none exists.

---

## 1.0-ready?

**Yes, for Android owner hardware**, provided final CI (including new `assembleRelease` step) is green on the tip of `main` after this commit.  

**Would block ship if still open:** R8 release failure (fixed), main-thread Featured seed ANR (fixed), wipe losing AutoFlow (fixed), summary version data loss on upgrade (fixed).  

**Does not block:** SoftAP security residuals, iOS, website, OAuth, P2P, other BLE devices.
