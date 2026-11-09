# Issue #345: device-walk Looper crash source diagnosis

- Inspected source head: `963663004e44f7a9b3816fc4c247a8053a2f6578` (`origin/main`, 2026-11-06 snapshot).
- Working tree initially clean; no tracked source changed. CodeGraph local ignored index initialized/synced as documented.
- GitHub issue body fetch failed (web cache miss); no issue text or live CI status available. This report therefore diagnoses the local exact-head source and commit history, not the currently rendered issue or live CI run.
- No builds, tests, emulators, heavy processes, external comments, or PR writes were run.

## Finding

The #345 playback Looper defect is already covered in current main. `AndroidAudioPlayer.prepare` switches to `Dispatchers.IO` at `AudioPlayer.android.kt:65`; before/after player preparation it routes player ownership and published playback StateFlow writes through `onMain` at lines 74–85 and 121–126. Failure cleanup likewise uses `NonCancellable + Dispatchers.Main.immediate` at lines 135–148. `onMain` is `withContext(Dispatchers.Main.immediate)` at line 157. The underlying boundary is necessary because StateFlow collectors can resume inline on the writer thread: an off-main state publication can run Compose recomposition/animation code off a Looper, triggering `Animators may only be run on Looper threads` (the source-level reproduction described in `AudioPlayerThreadTest.kt:31–35`).

The screen has a second affected boundary after `prepare`: `RecordingDetailScreen` calls `afterPrepare` at its two call sites (CodeGraph reports lines 200/other callsite); `AfterPrepare.kt:11–14` moves seek and unavailable-state updates onto `Dispatchers.Main.immediate`. For live transcription, the IO transcriber callbacks write screen state via the local `onMain` helper (`RecordingDetailScreen.kt:1287–1292`, used at 1329–1333); start and stop state paths also explicitly enter Main. These boundaries address sibling UI writes associated with #345/#202, not a remaining confirmed defect.

## Coverage and gap

- `AudioPlayerThreadTest.prepare_publishes_every_state_change_on_the_main_thread` (`composeApp/src/androidUnitTest/kotlin/dev/velum/app/platform/AudioPlayerThreadTest.kt:72–99`) starts `prepare` from worker threads, collects with `Dispatchers.Unconfined` to model inline collectors, checks every observed StateFlow write is on the test main thread, and exercises a second prepare/reset. The same file covers canceled/stale/failed prepares.
- `AfterPrepareTest` exercises worker-resumed callers and asserts both seek and error callbacks execute on Main.
- `LiveTranscriberHandoffTest` has cross-thread callback and Main-thread state assertions (source references include lines 531, 647–656); these cover the live-pane path.
- Historical local evidence: PR #383 merge `407968a129165d85c0607591761b3af78f6800f4` (“publish audio player state on the main thread”) added the Android player fix and `AudioPlayerThreadTest`. Later exact commits `b15c609431df7eaf3bdd673ac6f7838a652ff565`, `fd0dbc59248d88dd973778a9b399e47dea9594ee`, and `f51560f800a7dcab50f3e723a2dd1b9b62ca17ec` continue to harden live UI state paths, each explicitly naming #345/#202. These are local git-history facts; they do not prove current remote CI status.
- The focused tests are Robolectric/source-unit level. `DeviceWalkUiTest` is an emulator-wide shell walk and CI runs it on API 35 in debug and R8 release (`.github/workflows/ci.yml:241–314`), but the walk's high-level coverage is not a deterministic assertion that playback preparation/recomposition runs on Main. That is the narrow remaining test gap; no uncovered source write was found.

## Next task / gate

No product fix is justified from this snapshot. If stronger end-to-end protection is desired, add one deterministic recording-detail/device-walk step that opens a recording, invokes playback preparation while the UI observes playback state, and fails on an off-main write / Looper exception. Gate it with the existing `AudioPlayerThreadTest` plus the CI `android-device-walk` job (API 35 debug and R8 release). Existing #323 coverage was not proposed or revisited.
