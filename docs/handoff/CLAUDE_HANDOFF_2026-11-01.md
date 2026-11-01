# Velum: Claude handoff — 2026-11-01

Development was stopped at the owner's request. Only publication and handoff closeout continued. **Do not treat the unfinished drafts as ready to merge.** No feature fixes or new feature tests were run after the stop request was received.

## Start here

- Repository: <https://github.com/bloodf/velum>
- Verified remote `main`: `1f30a0f18fb959a6020606f50e82d5ac0bb2601d`.
- Handoff PR: **https://github.com/bloodf/velum/pull/132**. This is documentation only and is deliberately not merged. Resolve its current head from GitHub; its own hash is not embedded in itself.
- Ready-to-paste instructions: [Claude prompt](CLAUDE_PROMPT_2026-11-01.md).
- Complete inventory: [117 issues, including 101 open and 16 closed](ISSUE_COVERAGE_2026-11-01.md), with [machine-readable criteria/evidence/gaps](issue-coverage-2026-11-01.json).
- Many issue records remain unaudited against source. Completeness of the list is not completeness of the implementation. Canonical issues and current code take precedence over historical reports.

The latest owner direction before the stop was to prioritize identity integrity **#92**, then resume toolchain/R8 **#106**, followed by other feasible issues. There is no evidence that the project is release ready or defect free.

## Permissions and invariants

The owner authorized local development, commits, pushes, multiple PRs, safe merges after validation and closing issues whose criteria are proved. They explicitly chose **local verification rather than depending on CI** and said individual merges need no repeated confirmation. Check required protections/checks and never bypass them. Formal GitHub `APPROVE` reviews were not separately authorized; ask if a protection actually requires such approval.

Do not purchase services, create owner credentials, change security settings, publish releases/tags/store builds, or deploy beyond the known normal effects of an authorized merge without specific authorization. No real owner identity, microphone recording, user recordings or physical device commands were used in these batches. Continue using clearly synthetic fixtures. No identity values in chat, issues, logs, crash diagnostics or screenshots.

Never send opcodes `104`, `5`, `0xFE20`, `128`, `143` or write settings `4`/`26`. Keep the safety guard tests. No placeholders, account/login/telemetry or cloud paths in the open-source build. Read `ARCHITECTURE.md`, `CONTRIBUTING.md` and `docs/ENGINEERING_STANDARDS.md`; old issue text that prescribes direct-to-main/CI as the sole gate was superseded by the owner's PR/local-verification direction.

## Published work

Every merge below was checked against the exact locally tested tree. Main still contains all of them. No new merge was performed after the handoff request.

| PR | Scope | Tested head | Verified merge |
|---|---|---|---|
| [123](https://github.com/bloodf/velum/pull/123) | Release signing guard, genuine minified test harness, Android fixtures | `1651df7b8c6f446553d982497b4c48a2d5c3ed85` | `3de953851a4d3f21cb7bcd953c62587357f1ffbf` |
| [124](https://github.com/bloodf/velum/pull/124) | AutoFlow per-recording failure containment, cancellation/lifecycle | `ba001ca1ba14dbf8b3ab3dea5827757f0d4866bd` | `f33502fe7b4d28261f26140a010e018554a15136` |
| [125](https://github.com/bloodf/velum/pull/125) | Voice readiness, vault preservation, explicit profile recovery, credential failure UI | `217f74170073dbdc4c4e9def9ce02dac837a0259` | `d2cc3a2df5889cc14e858ed3c1b8659caa9f63b9` |
| [126](https://github.com/bloodf/velum/pull/126) | Mic permission/session ownership, WAV preservation, conservative draft reconciliation | `1c057aa00058768835b33f367c4e3cba1bb78ced` | `c5c77d359bd5949e9293e8ac463dba6911ef4398` |
| [127](https://github.com/bloodf/velum/pull/127) | Architecture and engineering standards | `ad4048c3f87e1779871b6542b3bf0ad87bddf1be` | `2d28d3231fce4e3bb10c453452cc133866d7ce46` |
| [128](https://github.com/bloodf/velum/pull/128) | Identity parser/UI diagnostic redaction and masked fields | `2f7ae21508ddb9e4ff42e4ee213796759cabe716` | `46268ae0cc3f654b78134126b52e4ccb5e93eda5` |
| [129](https://github.com/bloodf/velum/pull/129) | Real emulator Keystore persistence across distinct processes; isolated opt-in tests | `5b5157273faa840ed2ee8350368d9be31cc7fcad` | `1f30a0f18fb959a6020606f50e82d5ac0bb2601d` |

Issues closed with evidence during this task: **90, 112, 108, 109, 110, 105, 89**. The other nine closed issues predate this work and were not freshly reaudited. **92, 106, 115, 117, 86, 119, 122 and the other remaining requirements are not closed by these claims.** Historical PRs #2/#88/#120/#121 were closed without merge; do not infer that their titles represent landed implementation. PR #1 was already merged.

## Unfinished work: resume in this order

### 1. Identity integrity — draft PR #131 / issue #92

- [PR 131](https://github.com/bloodf/velum/pull/131), `codex/identity-import-atomicity`, head **`48b2a0210a9d450da81e4731e6b93bc0e4e75e4f`**, based on main `1f30a0f`.
- All unique unfinished source was committed and pushed. Eight files; no website edits. No uncommitted feature work remains in this worktree.
- Reproduction against main: a synthetic second per-field write failure leaves a new serial with the old signature/token; `hasKeys()` still returns true. Only synthetic in-memory storage was used for this probe.
- Prepared fix: versioned single-entry `device.identity_bundle_v1`; keep legacy field bytes/read compatibility without automatic migration; active bundle takes precedence; unreadable active bundle does not silently choose legacy material; cancellation before commit; explicit clear removes legacy entries before the active bundle.
- An uncertain provider commit can leave a complete new bundle despite an error. Do not promise rollback to the old active identity. Identity and template writes remain separate transactions.
- **Passed before closeout:** 66 focused security tests: core JVM25 and core Android41, including11 new deterministic failure/concurrency/cancellation/legacy cases.
- **Mutation proof:** the two failure/concurrent-reader regressions fail with old sequential writes. Corrected source was restored, SHA256 `4b57e17ad1700817bbfe7a56c82c56f80e1c9baddb2ca4d045f7127159c3bcf0` for `core/src/commonMain/kotlin/dev/velum/core/security/SecureStore.kt`.
- **Important:** the last JVM compilation used the deliberate mutant. Source is restored, but existing JVM class/JAR outputs are stale and unsafe as acceptance evidence. Rebuild from source before running or packaging.
- Later UI assertions and the three-vault JSON/paste/legacy emulator fixture were prepared but **not compiled or run**. The latter requires unchanged ciphertext hashes across reads/restart, encrypted private preferences and fixture-only cleanup. Its one added `SecureStore.putString` ABI keep is test-only.
- Pending: rerun focused tests after mutation, full affected regressions/lint/builds, UI failure/retry/redaction checks, actual debug/R8 persistence with the legacy fixture, normal runner restoration/package inspection and ordinary walks. Review all data-format/clear/concurrency semantics before merge. Keep #92 open until proved.

### 2. Kotlin/R8 metadata — draft PR #130 / issue #106

- [PR 130](https://github.com/bloodf/velum/pull/130), `codex/android-kotlin-metadata`, head **`76f403cfbc107a1f85cbe3c77a33ede6478a8145`**, also based on main `1f30a0f`.
- Four build/version files: AGP8.13.2, Kotlin/Compose compiler2.3.21, stdlib pin derived from the catalog, both `-Xskip-metadata-version-check` flags removed. Gradle8.14.3/JDK17 and Compose UI plugin1.9.3 retained. No broad keeps or warning suppression added.
- Verified compiled dependency annotations: LiteRTLM0.16.1 and kopus report metadata2.3.0. Actual old/new bundled R8 versions are8.10.24/8.13.19. Debug and minified app/test graphs resolve Compose UI/runtime/test1.9.4 and stdlib2.3.21.
- Passed strict debug/release/test Android Kotlin compilation and **327 JVM tests**: protocol43, BLE13, core126, AI133, recorder12.
- Full validation **failed**, before Android unit execution/R8 acceptance: `composeApp/src/androidUnitTest/kotlin/dev/velum/app/platform/WavWriterRecoverFinalizeTest.kt:14` and `:45` use `createTempDir`, now a Kotlin2.3 compile error. Replace with a private temp-directory API when development resumes; do not suppress the diagnostic or remove tests.
- No warning-free shipping/minified R8 result, full suite, matrix, signing or new persistence proof was obtained on this branch. Keep draft/#106 open.
- After #131 is integrated, incorporate verified main normally, resolve the overlapping test-ABI changes, update expected counts, and rerun on the exact integrated tree. Do not discard the identity fix by checking out an older branch's files.

### 3. Other concrete priorities and dependencies

- #115: automatic screenshots can precede destination content; supplementary settled captures proved content but did not fix the repository harness. Keep the full required journey and real Skip interaction.
- #117: prior SnapshotStateObserver concurrency cause remains unproved. Current walk's recreation/process-death marker is not genuine whole-app PID death. #129 proves secure-store persistence only, not whole-app restoration.
- Appearance theme selection can update the checkmark before the screen palette; `VelumTokenBridge.current` is a global nonobservable value set after composition. #87/#118 also include locale/accessibility/layout/pinned-topbar gaps. Mic native resource errors can use system rather than app language.
- #86 still buffers full download/decrypt audio; WAV bounded recovery is not full streaming decode. #113 still needs device/session deduplication.
- #119 lacks general startup cleanup-before-publication ordering; mic-only recovery/reconciliation is narrower. #122 generic orphan cleanup is unfinished; preserve audio/rows and handle import cancellation safely.
- #19 generic GATT API/sealed write boundary is absent; other recorder drivers and #111 test-fake relocation depend on its contracts. No physical commands without the required owner/device authorization.
- #99–103 template work: full catalog has16153 rows/16128 distinct exact bodies; Featured1500 contains100 repeated bodies across15 categories. Verify source and preserved template references before deduplication. #104 empty deprecated catalog object still exists; reference search found only its definition, but deletion is not done.
- #107 requires an actual independent fresh-context review; none was claimed. Give an independent reviewer immutable code/criteria, not earlier “ready” claims.
- Hardware/owner gates include real Note Pro/S26 identity/unlock and physical debug/release checks; API35 was unstable and remains unverified. Real ASR/model quality/thermal performance was not established by mocked processing.
- Desktop/macOS/Windows and iOS have separate roadmap gates. iOS cipher/runtime paths are not productized; desktop secure-store/mic assumptions must not be marketed as validated. OAuth/services/cloud/store release need actual owner registrations, credentials and product decisions. Do not invent placeholders or quotas.

## Local checkouts and preservation

| Path | State at closeout | Use |
|---|---|---|
| `/Users/heitor/Developer/github.com/bloodf/velum` | `main` at `1f30a0f`; only website4 dirty | User's original. Do not reset/clean/rebase or publish those edits. |
| `/Users/heitor/Documents/Codex/2026-10-31/task-2/publication-velum` | clean, `codex/identity-import-atomicity` at `48b2a02` | Current unfinished source. Both #130/#131 branches share this Git object store. |
| `…/task-2/publication-identity-persistence` | clean, `codex/identity-keystore-persistence` at `5b51572` | Merged #129 test baseline. |
| `…/task-2/publication-docs` | `codex/claude-handoff` | This documentation-only publication. |
| `…/task-2/velum` | main `16a77cb`,87 historical dirty files | Frozen archive. All87 nonwebsite file blobs exist in published main history. Do not reapply this older diff. |
| `…/task-2/publication-plan` | main `16a77cb`,56 historical dirty files | Older preparation copy; all56 blobs already in published history. No unique unfinished source. |

The common publication repository's local `main` ref can be stale. Fetch and branch from **`origin/main`**, not an assumed local ref. `git worktree list` shows the live publication worktrees. Old publication-audio/voice/autoflow worktrees were removed cleanly.

**Website4 remains uncommitted and excluded from all PRs:** `website/UNSLOP.md`, `website/src/Marketing.tsx`, `website/src/light-theme.css`, `website/src/styles.css`. Do not silently publish/revert them. Any newer user changes must also be preserved.

No task-owned build/test runner remains active. At stop, mutation had already completed and restored source; owned Gradle76310 and children9993/81797 were terminated. Preexisting emulator `emulator-5554`/qemu64307 and ADB were deliberately preserved, as were unrelated user processes. Reinspect PIDs/ownership; never kill all Java/Gradle processes. No physical device permissions/security settings were changed for this handoff.

All tracked #129 synthetic fixture aliases/preferences/metadata were cleaned, including diagnosed failed fixtures. #131's extended three-vault fixture has not run, so it created no new aliases. No production signing key or real owner credentials were created, read, copied or published. Packaging checks used disposable keys; any leftover APK/AAB is a test artifact, **not distributable**.

## Validation ledger

Do not add repetitions to claim a larger distinct suite, or reuse a historical matrix as a pass on a new head.

| Head/PR | Completed local evidence | Limits |
|---|---|---|
| `1651df7` /123 |462fresh tests;8/8API34 walks; debug/lint/desktop; signing checks | Original concurrency cause not proved; test-only exact coroutine singleton ABI added. |
| `ba001ca` /124 |AI102+app113;50task reruns ×25tests;2selected walks; builds/lint | Synthetic processing; no hardware claims. |
| `ecb1568` then `217f741` /125 |564full tests on predecessor; final app133;20×38 screenshot cases with loaded-profile wait;4selected walks and4settled captures | Earlier spinner captures are not visual acceptance; final helper differs only in test code. |
| `1c057aa` /126 |706fresh tests;8/8API34 walks;8filesystem executions;8supplemental contexts; signing/package verification | Last complete eight-cell integrated matrix before subsequent PRs. Metadata warnings remained. |
| `ad4048c` /127 |43relative links; exact Product rules block; code references reviewed | Docs only; runtime tests not rerun. |
| `2f7ae21` /128 |726fresh tests;4selected walks;8filesystem;4actual import-screen cases,8screenshots inspected; redaction mutation proof | Selected matrix is4, not8. |
| `5b51572` /129 |372fresh Android units;6fixture phases;4standard walks;8filesystem; packaging/runner/security flags checked | No production runtime change; all fixtures cleaned. |
| `76f403c` /130 |Strict Android production/test compile; graphs aligned;327 JVM pass | Full build failed on deprecated test API; no final R8/matrix. |
| `48b2a02` /131 |66 focused core tests;2expected mutation failures and exact source restoration | UI/extended emulator changes not compiled; full checks unrun; JVM outputs last contain mutant. |

Device walks use synthetic audio/processing. R8 remains enabled; minifiedTest is debug-signed for installation, not a shipping release. API35 and physical hardware remain unverified. Old CI reports are diagnostic and may be stale; no blanket “CI green” claim was made.

## Reproduce locally

Set `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home` and `ANDROID_HOME=/Users/heitor/Library/Android/sdk` on this Mac. Check these paths before use. `/opt/homebrew/bin/python3.14` has Pillow; the unversioned Python differs by shell and may lack it. Use no production `VELUM_UPLOAD_*` values during tests.

For #131, rebuild the core JVM source first, then rerun focused tests and the actual UI import tests:

```sh
./gradlew :core:compileKotlinJvm --rerun :core:jvmTest --tests 'dev.velum.core.security.*' --rerun :core:testDebugUnitTest --tests 'dev.velum.core.security.*' --rerun --max-workers=2 --no-build-cache --console=plain
./gradlew :composeApp:testDebugUnitTest --tests dev.velum.app.di.DeviceKeyImportStorageUiTest --rerun --max-workers=2 --no-build-cache --console=plain
```

Then run all five backend JVM tasks, core/app Android units, desktop platform tests, lint/debug/shipping R8 compilation. Expected pre-#131 main counts are protocol43/BLE13/coreJVM126/AI133/recorder12/coreAndroid114/appAndroid258/desktopPlatform27=726. #131 adds11 core tests per JVM/Android target; verify real fresh XML counts rather than copying a number.

```sh
./gradlew :plaud-protocol:jvmTest --rerun :ble:jvmTest --rerun :core:jvmTest --rerun :ai:jvmTest --rerun :recorder:jvmTest --rerun :core:testDebugUnitTest --rerun :composeApp:testDebugUnitTest --rerun :composeApp:desktopTest --tests 'dev.velum.app.platform.*' --rerun :composeApp:lintDebug :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest :composeApp:minifyReleaseWithR8 :composeApp:compileKotlinDesktop --max-workers=2 --no-build-cache --console=plain
./gradlew :composeApp:assembleMinifiedTest :composeApp:assembleMinifiedTestAndroidTest -PvelumTestBuildType=minifiedTest --max-workers=2 --no-build-cache --console=plain
```

For genuine storage process restart, use the committed `scripts/check-identity-persistence.py` and the opt-in build commands in [PLAUD_IMPORT.md](../PLAUD_IMPORT.md). It requires an explicit emulator serial, clean committed source and a new empty evidence directory. Rebuild normal test APKs without `velumIdentityPersistenceTest` afterward, inspect the actual runner/classes and run the standard walks. On this task's source relocation, task-specific nonincremental test recompilation removed a stale runner class; do not remove source/test assertions to hide stale outputs.

For each variant `debug`/`minifiedTest`, install its app and Android test APK with `adb -s emulator-5554 install -r -t`, then run `dev.velum.app.devicewalk.DeviceWalkUiTest` via `dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner`, passing `-e velumDeviceWalk true`. The four methods are `deviceWalk_ptBr_light`, `deviceWalk_ptBr_dark`, `deviceWalk_en_light`, `deviceWalk_en_dark`. Test `WavRecoveryDeviceTest` has4filesystem cases. Preserve coverage files and fresh screenshots. Require43PT/45EN markers with zero soft misses. Never mark onboarding complete as a substitute for real Skip.

`bash scripts/check-release-signing.sh --package` checks missing/partial/blank credentials and disposable-key APK/AAB packaging. Also inspect actual packaging task guards, certificates and app/test dependency graphs after AGP changes. Never distribute these packages. The existing `scripts/emulator-launch-smoke.sh` clears package data: **do not run it against an emulator containing user data**. Use a separately owned disposable test environment or the non-clearing direct journey commands.

## Local-only evidence inventory

Root: `/Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/`. Raw logs and machine-specific drivers were deliberately not committed. The following are retained locally; do not publish them indiscriminately.

- `final-integrated/`: old frozen-source hashes, full matrix/validation and website preservation.
- `publication/pr1-*` through `pr4-*`: per-PR tests, builds, signing, matrices, source/tree/merge receipts. `pr4-matrix/` is the eight-cell #126 matrix; `pr4-supplemental/` has settled destination captures.
- `publication/docs105-*.json`: document checks/verified #127 merge.
- `publication/identity-redaction/`: #128726tests, mutation,4walks,4actual UI contexts/contact sheet, final review/merge/original fast-forward receipts.
- `publication/identity-persistence/`: #129 `final-review.json`, `verified-merge.json`, `process-and-packaging-evidence.json`, `final-persistence/`, `normal-walks/`, source/APK receipts, earlier attempts and explicit fixture cleanup.
- `publication/identity-atomicity/`: #131 `targeted-result.json`/XML, `mutation-result.json`/XML, `run-mutation.py`; source restored but no post-mutation full rebuild. `publication/identity-atomicity-probe-result.json` records the main-branch defect using booleans only.
- `publication/toolchain-106/`: #130 candidate compile, actual R8/dependency graph reports,327 JVM/full-build failure, exact four-file patch and reproducible `run-validation.py`, `run-full.py`, alignment init script. These local drivers pin old heads/counts; review/adapt them before reuse.
- `publication/handoff/`: original/local worktree inventory, archived-copy blob reconciliation, process stop receipt and local public issue snapshot. This supports the sanitized committed inventory; raw snapshot bodies were not copied into the handoff.

The older audit documents contain conditional/stale “ready” statements. Use the source hashes, exact-head ledger, open issues and fresh verification instead. Do not infer whole-project readiness from issue count, test count or a successful merge.
