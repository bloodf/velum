# Maintenance pass 2026-11-22: PR queue, issue triage, code review

Scope: the open PR queue from the snapshot (`prs/open`, 55 PRs), the open issues (93), then a code review of the
application. Base: the `main` snapshot. Everything below was validated locally; the environment limits are in
[Validation](#validation).

## 1. PR queue

### Merged into `main`

| PR | What | Review follow-up in the merge |
|---|---|---|
| #542 | CRC-mismatch verdict persisted with the checkpoint (length + SHA-256) before bytes reach the caller (#212) | Desktop tests now depend on `:ble-testing` (the PR's jvmShared test did not compile for desktop) |
| #543 | Android Wi-Fi -> BLE fallback through the simulated peripheral (test only) | - |
| #539 | Stale foreground-service timeouts cannot cancel a newer download (#85) | A throwing cancel handler is caught in `onTimeout` instead of crashing the app |
| #540 (commit 1 of 5) | 1 GiB cap on provider copies (picker + share), `CREATE_NEW`, only owned files deleted | Commits 2-5 not merged, see below |
| #546 | Desktop WAV import streams, validates RIFF/fmt, hashes the output | A `data` chunk longer than the file keeps the bytes present (parity with Android) instead of rejecting the recording |
| #524, #520, #522, #521 | iOS: PBKDF2 bytes/counts, empty HMAC inputs, bundled catalogs, session spool | - (#524 also makes `core` iOS tests compile) |
| #533 | iOS Opus decoding (AudioToolbox) | Did not compile: missing `kotlinx.cinterop.free` import, fixed |
| #502 (Android/common half) | LiteRT-LM async generation + cancellation, async model verification, tar reader, Whistle gating | Single-file models (2.5-3.6 GB) were re-hashed on every start: verification memoized by size+mtime. Switching a task to Local checked the saved mode (see 3.1) |
| #503 + #505 | Physical-only benchmark reports, schema v2, fail-closed metadata (#98) | Only the benchmark commits (the #502 commits they carried were already merged) |
| #507 | Opt-in LiteRT cancellation/restart device probe | Skipped (JUnit `Assume`) when `-e modelPath` is absent, instead of failing |
| #537 (Android/common half) | Suspend + cancellable `StreamingTranscriber` lifecycle | - |

### Kept on review branches (need macOS/Xcode to build)

These change iOS-only code that cannot be compiled here (Kotlin/Native with Apple cinterop, Swift, or `composeApp`
iosMain whose Apple dependencies are not available). They are rebased on this `main` and conflict-free:

- `review/ios-local-inference`: iOS half of #502 (Swift LiteRT/Sherpa bridge, bzip2 cinterop, iOS model store and
  runtime), iOS half of #537, #538 (libarchive ZIP extraction).
- `review/ios-composeapp`: #534 (includes #530 and #517), #516, #518, #519, #531, #532, #535, iOS commits of #544,
  #536 (its libsodium binding is not resolvable here). Its shared-code changes (`AudioImportPipeline`,
  `IncomingShare`) compile for Android/desktop and `DetachedShareImportTest` passes (11/11).

### Not merged

| PR | Reason |
|---|---|
| #351 | Diagnostic, superseded by merged #360 (its own author says so) |
| #540 commits 2-5 | Private `:provider_copy` process: `VelumApplication` aborts at start-up when the process name is unknown; `ProviderCopyRetirement.awaitPrevious()` has no timeout, so a copy child that never dies blocks every later import; the Binder path is not exercised by the Robolectric tests (they install a fake transport) |
| #551 | Older snapshot of #540 |
| #555, #568, #573 | Older snapshots of #502 |
| #569, #570 | Older snapshots of #503/#505 |
| #564 | #542 without its last test commit |
| #571 | Already in `main` |
| #572 | Incomplete fragment (passes a `userConsented` argument `importNow` does not take); the consent flow is already in `main` (#508) |
| #547-#550, #552-#554, #556-#563, #565-#567 | Evidence notes and agent state as files at the repository root (`evidence/`, `.omc/`, receipts). The diagnoses for #202, #285, #319, #345 and the queued-recovery CI run all conclude "already fixed on main, no code change". |

## 2. Issues

No open issue was closable outright on code alone except where noted; most remaining criteria need a physical device,
an owner decision or an external service. Status as of this pass:

- **Hardware/physical device needed**: #3-#9, #11-#14, #85, #86, #94, #96, #98, #103 (real-execution criteria), #319
  (device timing), #323 (Note Pro clock), #331 (posted notification on a device), #357 (real SoftAP close behaviour).
- **Owner decision / account / service registration**: #25-#29, #37, #49, #50, #74, #77, #83, #107, #204 (tag control
  design; the 24-tag cap is already removed), #212 (wrong-key deletion), #432, #18.
- **Flakes waiting for CI evidence**: #160, #202, #285, #311, #345.
- **Feature work not started**: desktop D1-D5 (#30-#34), integrations (#51-#61), sync (#65-#73), cloud (#75-#82),
  devices (#15-#17), website (#23, #24).
- **Appears met in code** (owner to confirm and close): #348 - processing/engine errors map to localized text and
  unknown ones are not shown, the fatal screen and cold-start notifications use the in-app language (persisted hint,
  `ColdStartLanguageTest`), thermal/progress messages never reach the UI.

## 3. Code review: defects fixed (each with tests)

### 3.1 Correctness
1. **AutoFlow webhook / folder export silently dropped** when a recording finished with no screen open (WorkManager
   after the activity was gone): the runner emits into a SharedFlow without replay and only the composition collected
   it. New `AutoFlowActionRouter` is the process-lifetime collector: it runs those two actions itself and queues the
   rest for the next screen; a slow webhook does not hold back later actions. (`AutoFlowActionRouterTest`,
   mutation-checked)
2. **Ask AI overflowed the on-device model's context**: the whole transcript and the whole chat history went into the
   prompt. Now excerpts chosen for the question within a budget (small for local, large for API). (`AskAiChatContextTest`)
3. **Duplicated words at every 28 s ASR window boundary** (2 s overlap transcribed twice and concatenated).
   (`WindowedAsrTest`)
4. **API transcripts had no speaker labels or timestamps**: text-only results gave diarization nothing to label and
   Ask citations pointed at 0:00. Window-long segments now, provider-labelled text kept as is. (`ApiTranscriptSegmentsTest`)
5. **Word-level transcripts (Whistle) were written one word per line**, also in exports/shares/webhooks.
   (`TranscriptFormatterTest`)
6. **WAVs that are not 16-bit mono PCM were transcribed as noise** and the job reported success. (`RecordingProcessorDecodeCleanupTest`)
7. **Switching a task from API to Local saved Local without the model**: the check read the saved mode. (`ResolveModeOrPromptTest`)
8. **API summaries failed with reasoning models** (temperature other than default -> HTTP 400) and a null/blank reply
   was a parse error or an empty summary. (`OpenAiCompatibleClientTest`, `ProcessingFailureTextTest`)
9. **Spurious errors from cancelled coroutines**: leaving Discover during the full catalog import showed "import
   failed"; a search cancelled by the next keystroke could leave a false error state. (`FullCatalogImportRunTest`)
10. `http://10.0.2.2` (emulator host) rejected although the cleartext network config allows it. (`AiBaseUrlTest`)
11. **Two speakers could be given the same person's name**: each speaker was matched to its best voice profile on its
    own. Now best-scoring pairs first, one profile per speaker. (`VoiceProfileStoreTest`, mutation-checked)
12. Refreshing the model list before saving an API key said "Could not list models" instead of asking for the key.
    (`ModelsRefreshErrorTextTest`)

### 3.2 Performance
- Start-up no longer re-hashes multi-GB single-file models (see #502 above). (`ModelVerificationStampTest`)

### 3.3 Test infrastructure
- `AndroidWifiSoftApJoinerTest` runs on SDK 26/28/29 but only SDK 34/35 Robolectric jars were provisioned offline, and
  CI never ran `:ble:testDebugUnitTest`: jars pinned (Maven Central SHA-256) and the library Robolectric suites added
  to CI.
- `DesktopAudioPlayerTest.releaseInvalidatesPendingPreparation` was timing-dependent; made deterministic.

## 4. Recommendations (not changed)

- Watched-folder sync copies and hashes every file on every sync, and auto-sync runs at each app start. Not changed
  because `watched_content_identity_detects_replacement_and_retries_failures` requires detecting replaced content with
  identical URI, timestamp and size. Options: hash from the provider stream without the cache copy, or throttle
  auto-sync.
- Speaker attribution with the Sherpa engines is per 30 s window (one segment per window); splitting by token
  timestamps needs model-specific detokenization and real models to test.
- `speaker.recording_id` and `summary_version.recording_id` have no index (needs migration `6.sqm` + snapshot).
- Diarization of spilled audio covers the first 30 minutes (documented memory bound).

## Validation

- JVM tests, Android (Robolectric) unit tests of every module, composeApp desktop tests, `validateAutoflowTemplates`,
  iOS arm64 klib compilation of `plaud-protocol`, `core`, `recorder`, `ai`, `ble` (main, and tests where their
  dependencies resolve), composeApp Android + desktop compilation.
- Environment limits (sandbox only, nothing committed): JitPack/GitHub unreachable, so sherpa-onnx, requery and
  LiteRT-LM were replaced by API stand-ins for compilation, and kopus by a JNA binding to the system libopus (real
  Opus decoding in tests); Android platform 36 was platform 35 relabelled; some Compose/AndroidX versions were pinned
  to the nearest available ones. 51 composeApp Robolectric test files need `ui-test-junit4`, `work-testing` or
  Roborazzi, which were not available, and did not run; nor did lint, the desktop screenshot renders, instrumented
  tests, or anything on a physical device or simulator.
