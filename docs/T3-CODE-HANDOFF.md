# Velum — t3 code continuation handoff

This is an execution handoff, not a request to restart planning. Continue the existing work from its preserved branches and exact revisions. Verify current state before acting; do not redo completed changes.

Snapshot: 2026-11-04, 20:12 America/Sao_Paulo. The final compilation result for PR #451 was not confirmed at handoff. Recover its logs or rerun that gate before pushing.

Model routing requested by the owner: GPT 6 Astra for orchestration; GPT 6.1 Sol for large work; GPT 6.1 Terra for medium work; GPT 6.1 Luna for small work, discovery, QA and fast review. Verify actual provider IDs.

## Continuation prompt

You are taking over autonomous development of bloodf/velum in t3 code.

MODEL ROUTING
- Main orchestrator: GPT 6 Astra.
- Large features, concurrency, architecture, difficult debugging: GPT 6.1 Sol.
- Medium features and normal implementation: GPT 6.1 Terra.
- Small fixes, file discovery, focused QA and fast reviews: GPT 6.1 Luna.
- Use the exact provider/model IDs configured in this environment. These names describe requested routing, not verified API identifiers.
- Keep implementation and approval independent. An author cannot approve their own work.
- Aim for about 10 useful concurrent agents when independent work exists. Do not invent work or duplicate investigations to fill a quota.
- Parallel code work uses separate git worktrees. Local Gradle/build execution is sequential across workers.

MISSION
Continue every feasible open GitHub issue until work is implemented, tested, independently reviewed, PR-ready and safely merged. Prepare the project for stores, but do not publish releases, tags, store builds or submissions.

Keep an autonomous event-driven loop active. Background completion should trigger the next action; keep a 25-minute fallback heartbeat. Do not confuse ending an assistant turn with ending the project loop.

Blocked hardware, credentials, legal decisions and owner decisions remain open and explicitly reported. Never claim all issues or store readiness are complete while those remain unresolved.

INITIAL REPOSITORY STATE — VERIFY AGAIN
Repository:
  /Users/heitor/Developer/github.com/bloodf/velum

GitHub:
  https://github.com/bloodf/velum

Main at handoff:
  55c4974c7dc3f3e032d5e61aaa7f7d6b7049e6b1

Recent main merges:
- #420: durable imported audio and migration; merge 55c4974c.
- #450: acknowledged scheduler enqueue and recovery KEEP; merge 16eace89.
- #449: damaged-salt partial deletion; merge c5b1da53.
- #441: SQLite/FTS wipe compaction; merge 6c3d568a.
- #438: delete during device transfer; merge d1ded105.
- #448: template regeneration regression tests; merge 6d19391a.
- #443: initial stranded queued-row recovery; merge 37a081f9.

99 issues were open at snapshot. Query GitHub again.

Owner checkout has these existing changes. NEVER edit, stage, discard or overwrite them:
  website/src/Marketing.tsx
  website/src/light-theme.css
  website/src/styles.css
  website/UNSLOP.md

Current open PRs:
- #451: active deletion/admission work, remote head still
  826de6bb7cdc60f3a6fa030c0cd5e9edaed8fc6c.
  Better local head exists; see immediate priority below.
- #372: old processing-failure localization PR.
- #359: old import title/duration/source-label PR.
- #356: old AutoFlow markers-only transcript test PR.
- #351: diagnostic, explicitly DO NOT MERGE.
- #315: old wipe-status PR, apparently superseded.
- #132: old handoff document, not for merge.

Do not close these old PRs, delete branches, or alter labels/draft/reviewers without explicit owner direction. Do not merge merely because an old PR appears green.

NON-NEGOTIABLE SAFETY AND OWNER RULES
- Never force-push or rebase a published branch. Use normal merges.
- Never edit .github.
- Never bypass branch protections or required checks.
- Never kill foreign processes. No pkill/killall java/gradle, gradlew --stop, or global daemon cleanup.
- Never delete or repair ~/.gradle.
- Never create credentials, spend money, change security settings, publish releases/tags/store builds, or create v1.0.0.
- No AI attribution in commits, PRs, issue comments or other code-host output.
- No account, telemetry, placeholder or cloud paths in the OSS build.
- Synthetic fixtures only.
- Never expose device identity, keys or credentials in chat, logs, issues or screenshots.
- Never send BLE opcodes 104, 5, 0xFE20, 128, 143 or 30. Opcode 30 is receive-only.
- Never write device settings 4 or 26.
- Never checkout/change branches inside another worker's worktree.
- Before pushing, verify cwd, symbolic-ref, clean status and exact HEAD.
- Close an issue only when every criterion is proved, or the criterion explicitly permits a documented gap.
- Current autonomous owner directive authorizes completing issues, commits, pushes, reviewed green merges and evidence-based issue closure. It does not authorize unrelated PR closure, branch deletion or releases.

WORKTREE AND BUILD CLEANUP
Owner explicitly requests removal of obsolete worktrees, dist, temporary files and development waste.

Inspect before deletion:
- Preserve active, dirty and unmerged worktrees.
- Preserve test evidence needed for pending reviews and issues.
- Use git worktree remove without --force for clean obsolete worktrees.
- Do not delete branches.
- Do not run broad git clean -fdx or recursive deletion across shared directories.
- Never touch another project's files/processes.
- Ten clean merged stale worktrees were already removed. Inventory again; do not assume old paths still exist.

TOOLCHAIN
Every Gradle invocation:
  GRADLE_USER_HOME=$HOME/.gradle-velum
  JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.1.jdk/Contents/Home
  ANDROID_HOME=/Users/heitor/Library/Android/sdk

Shared host is heavily loaded and variable. Load averages reached hundreds.
Do not stall indefinitely waiting for load <=12 for normal development.
That threshold belongs ONLY to the predeclared #319 timing experiment.
For ordinary scoped verification:
- One Gradle invocation at a time.
- nice -n 10 when appropriate.
- --max-workers=1 --no-build-cache.
- --rerun-tasks for fresh tests/mutation checks.
- Keep source frozen while a command runs.
- Observe actual resource failures rather than treating load average alone as a stop condition.
- No full suites inside every agent. Orchestrator runs one full merged-main regression per batch.

Emulator:
  /Users/heitor/Library/Android/sdk/platform-tools/adb

Owned AVD:
  galaxy-s25, emulator-5554.

Foreign:
  emulator-5570 and emulator-5580; never touch them.

Before any emulator control, verify:
- Explicit serial.
- Correct AVD name.
- TCP listener belongs to launched process or its descendants.
- Ports 5554/5555 are not owned by another process.
- Shut owned emulator down immediately after walk.
- Wait for owned process and ports to disappear before reporting it off.
- Never run emulator-launch-smoke.sh against user-data emulator.
- Do not use connectedDebugAndroidTest locally; use am instrument.

IMMEDIATE PRIORITY: FINISH PR #451
Issue:
  #387 — Delete forever and Empty trash leave audio on disk.

Active worktree:
  /Users/heitor/Developer/github.com/bloodf/velum-387-admission

Branch:
  fix/387-admission-fence

Exact local HEAD at handoff:
  451944db9c0cb95f05e430533951d08c140bbdf9

Status:
- Clean.
- Ahead of remote PR branch.
- Not pushed.
- Remote PR #451 remains at 826de6bb.
- Local main is already 55c4974c, containing #420 and #450.
- No GradleWrapperMain process appeared in final process check.
- DO NOT infer final compile success from absent process.
- Previous executor was stopped; attempt to resume was rejected. Start a fresh t3 worker with these paths and inspect preserved logs/results.

WHAT #451 IMPLEMENTS
- Repository-aware per-recording scheduler admission.
- Explicit scheduler outcomes:
  STARTED_OR_REPLACED / KEPT / REJECTED.
- Correct handling of unknown WorkManager request identity.
- WorkManager lookup includes terminal requests, not only active requests.
- Atomic processor deletion fence and admission registration.
- Cancellation acceptance awaited off Main.
- Cancellation ownership survives caller timeout long enough to settle pending operation.
- Registered processor children stay tracked through actual cleanup completion.
- Bounded waits retain row when shutdown is unconfirmed.
- Microphone ownership retained on failed/unconfirmed stop.
- Mic lock and scheduler lock ordering avoids recursive/deadlocked acquisition.
- Mic capture checks deletion fence and existing row/source/status.
- Partial recovery coordinates file/DB writes under mic ownership.
- Late summary/speaker writes cannot recreate deleted-row data.
- Existing #438/#449 device-transfer cancellation and partial deletion preserved.
- AutoFlow uses actual kept/started/unknown semantics; Operation success alone is not proof a new job started.

IMPORTANT LOCK ORDER
Processor fence first.
Serialize scheduler row-check + submit + Operation acceptance/cancel.
Release scheduler lock BEFORE acquiring mic mutex, because MicFinalize already holds mic mutex while enqueueing.
Do not perform synchronous DB access on Main.
Do not replace durable row-existence checks with in-memory-only tombstones.

#451 REVIEW HISTORY AND VERIFIED EVIDENCE
1. 826de6bb:
   - 176 tests passed:
     Android112 + AI62 + core2.
   - Android/desktop/iOS simulator compilation passed.
   - Static independent approval.
   - Earlier mixed KEEP test returned handed=0 once; cause remained unproved.
     Subsequent isolated/instrumented and repeated uninstrumented runs passed.
     This residual flake is disclosed, not claimed fixed.

2. CI run 41925733391 failed:
   LiveTranscriberHandoffTest.
   leaving_during_the_transcript_wait_still_finalizes_and_queues

   Exact symptom:
   owner expected null while recording ID still present.
   Transcript, status and queue assertions passed.

   Root trace:
   fake queued++ occurs on Dispatchers.IO inside scheduler admission;
   MicFinalize releases owner later when continuation resumes.
   Test observed enqueue submission, not completed finalization.
   Counter also crossed threads without AtomicInteger.

3. ec24068946cdda86969deaef0880a2ec72817b2a:
   Test-only fix:
   - All fake queued counter reads/writes use AtomicInteger.
   - Bounded existing wait checks queued==1 AND owner==null.
   - Transcript/status/owner checks retained.
   - Exact-one enqueue check added.
   - No sleeps or production changes.
   - Original CI failure reproduced locally before fix.
   - LiveTranscriberHandoffTest41 + MicFinalizeTest19:
     60 passed, zero failures/errors/skips.
   - Independent static approval.

4. ac64a83b460d9f65093c39d07bcf69c72a5bc18f:
   Normal merge of current main55c4974c, no textual conflicts.
   Contains durable-import work from #420.

5. 451944db9c0cb95f05e430533951d08c140bbdf9:
   One-line ImportStorageTest fake adapter now returns
   STARTED_OR_REPLACED instead of Unit for new scheduler API.
   No production change in that adapter commit.
   Thirteen impacted Android classes passed.
   Exact combined count was still being collected.
   Android/desktop/iOS simulator compilation was started afterward.
   FINAL COMPILATION RESULT IS UNKNOWN AT HANDOFF.
   Independent reviewer explicitly APPROVED #451-only static integration
   at this exact SHA.

Reviewer scope:
- No blocker introduced by #387 or merge interaction found.
- Upstream issues #452/#453 remain separately tracked.
- Review was static; do not treat it as runtime verification.

NEXT ACTIONS FOR #451
1. Inspect clean worktree and exact HEAD.
2. Locate final compile logs/test XML; prove whether command completed.
3. If no reliable completion evidence, rerun required compilation sequentially.
4. Confirm exact counts for the thirteen impacted classes from fresh XML.
5. Preserve residual KEEP-flake disclosure.
6. Push local branch normally to existing remote branch, no force.
7. Update PR #451 with exact-head evidence and CI-failure fix.
8. Wait for green required checks.
9. Merge exact approved head safely.
10. Inspect #387 criteria; close only proved criteria.
    Do not silently treat desktop gap #452 as fixed.
11. Run full regression on resulting main.

#450 / ISSUE #415 — COMPLETE AND MERGED
PR #450 merged as:
  16eace89bae4c2b07aaa685e0010e514448996b6

Issue #415 was closed with evidence.

Worktree retained:
  /Users/heitor/Developer/github.com/bloodf/velum-415
Branch:
  fix/415-enqueue-operation
Verified head:
  597318cae238b68c1936a4bf07636b4b94a1e047

Behavior:
- Saved enqueue awaits actual WorkManager Operation acceptance off Main.
- Failure marks only still-queued row failed.
- Accepted submission cancellation does not falsely mark job failed.
- Mic ownership releases correctly after saved handoff.
- Startup recovery uses atomic KEEP.
- Matching AutoFlow template/model recovered.
- Wipe respected.

Final evidence:
  QueuedRecoveryTest13
  MicFinalizeTest19
  RecordingWorkKeepTest8
  RecordingJobSchedulerTest6
  46 passed, zero failures/errors/skips.
  Desktop and iOS simulator compilation passed.
  Exact-head independent static approval.
  CI green before merge.

Fixture issue found and fixed:
AppContainer starts AutoFlow when settings become Ready.
AutoFlow could enqueue seeded matching row before manual recovery.
#415 fixture canceled/joined appScope before seeding because its then-current
WM implementation did not depend on that scope.
#451 cancellation DOES depend on appScope, so blindly importing that fixture
would invalidate tests. #451 instead stops/joins actual AutoFlow observer,
preserving appScope until teardown.

#420 / ISSUE #318 — COMPLETE AND MERGED
PR #420 merged as:
  55c4974c7dc3f3e032d5e61aaa7f7d6b7049e6b1

Issue #318 closed with evidence.

Recovery worktree:
  /Users/heitor/Documents/Codex/2026-11-03/recovery-318
Branch:
  recovery/imports-318
Final merged head:
  a571738179bd4d3a4ffdae708f8876cc9ad268f8
Production upgrade-tested head:
  292f7b4e89f37687d433644ef3ee638e63e5807a

Features:
- Android normalized imports stored under app files/recordings.
- Unique per-attempt owned output paths.
- Bounded UUID-based ASCII scratch names for picker/share/watched imports.
- Original display names separate from paths.
- Pending-share display names persisted with backward-compatible markers.
- Raw scratch cleaned after success/failure/duplicate/cancellation.
- Watched batch cleanup handles unprocessed copies.
- Migration retains shared sources until every referencing row succeeds.
- Existing destination only reused when verified against source.
- Missing-source arbitrary destination adoption refused.
- Migration before container publication and runtime startup.
- Wipe cache cleanup retained.

Verification:
- 70 targeted import/migration/picker/share/watched tests passed.
- Desktop compilation passed.
- Watched byte-bound and recovered-title mutation tests failed as expected.
- CI initially found four old watched-retention assertions.
  Test-only final a571738 updated lifecycle expectations while preserving
  winner audio, loser cleanup and permanent deletion.
- ImportAtomicityTest3 + ReimportAfterDeleteTest35:
  38 passed, zero failures/errors/skips.
- Independent exact-head review.
- Green CI before merge.

REAL EMULATOR UPGRADE PASSED
Old:
  6c3d568a4a1783441cc7e3390eb59d4f9a0f320b
New production:
  292f7b4e89f37687d433644ef3ee638e63e5807a

install -r without app-data clear.
Synthetic WAV moved from cache/imports/normalized to files/recordings.
Length:
  96044 bytes
SHA-256 unchanged:
  fca2999e2f1422e4dfdb6814bd3954853eae391a2b5da76170a4e1bef12e093a
Old cache file removed.
Owned galaxy-s25 shutdown verified; ports free; adb devices empty.

UPGRADE EVIDENCE
Scratch root:
  /private/tmp/claude-501/-Users-heitor-Developer-github-com-bloodf-velum/2b6d6db5-3864-43a7-89f9-3f7ee3c9e104/scratchpad

Files:
  upgrade-420.sh
  ImportUpgradeDeviceTest.kt
  upgrade-420/seed.txt
  upgrade-420/verify.txt
  upgrade-420/ownership.log
  upgrade-420-build.log

Last executed script SHA-256:
  4a983ab3520f865ff0f6b081c5825f6d3896fe934df4f7cfd839bd4fa4c02881

Injected instrumentation test SHA-256:
  0e52ee6b12b61a8104960d57161beb3f6fe420a6ee81b5b5bbc7abe722fc1e1c

Old app APK SHA-256:
  50949f02b1e524f739319f2e29dd88e447dcdd482b3d4d3b32c56726a6415b2b
New app APK SHA-256:
  20a2aaf89dab4cb58ab6dd82999fa7415d76cada544a023e29feb79f613e2771
Test APK SHA-256:
  2be051e1a1ce4eb38386568c173a20b55f7d29f3df71312c9d110619e2643f01

NEW FOLLOW-UPS — OPEN
#452 Desktop import output survives permanent deletion:
- AudioNormalize.desktop.kt writes source.parentFile/normalized.
- RecordingFiles.desktop.kt recognizes only ~/.velum/recordings.
- Permanent deletion can remove row while generated normalized copy remains.
- Original user-selected source must NEVER be deleted.
- Found statically, runtime repro still needed.
- Predates #451 integration.
- Desktop implementation subject to owner gate #29.
- Test: import external synthetic WAV, delete recording, original remains,
  generated copy gone.

#453 Watched metadata shortcut misses changed content:
- WatchedFolder.android.kt uses URI+mtime+size as unchanged authority.
- Same-size replacement preserving mtime is skipped before content hash.
- Introduced upstream by #420, not #387.
- Needs content identity or provider-guaranteed immutable version.
- Reuse existing streaming hash/dedupe; metadata alone not proof.
- Test same URI/size/mtime with changed bytes imports new content.
- Unchanged content must not duplicate; failed imports must retry.
- No runtime reproduction yet.
- Keep separate PR; do not expand #451.

OTHER RECENT COMPLETED ISSUES
- #447 damaged-salt orphan deletion fixed by #449.
  20 focused tests; old early-return mutation fails3.
- #407 delete during transfer fixed by #438.
- #423 database free-page/FTS remnants fixed by #441.
  Android real-driver control failed, fixed head passed.
  FTS optimize before VACUUM, checkpoint busy checked.
  iOS and framework fallback limitations explicitly stated.
- #391 import atomicity fixed by #411.
- #429 API-key prefix wipe fixed by #442.
- #328/#405/#312 closed earlier with evidence and explicit limitations.

FULL REGRESSION
Last completed orchestrator full run:
  main6c3d568a
  1913 tests, zero failures/errors, 7 skipped.
  Android assemble and iOS simulator compile passed.

This predates later main merges. A fresh full suite is owed after current batch.

Existing scripts:
  /Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/tools/run-main.sh
  /Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/tools/after-queue.sh
  /Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/tools/merge-when-green.sh

Read scripts before running.
run-main.sh uses a dedicated wt-main-check worktree; verify path still exists
and is not used elsewhere before checkout.
merge-when-green.sh can exit0 after SKIP, including red CI.
ALWAYS read output and verify GitHub state; exit0 is not proof of merge.

NEXT PARALLEL ASSIGNMENTS
Astra orchestrates and owns merge order/evidence.

Sol:
- Finish #451 final integration verification and CI follow-up.
- Security/concurrency changes needing independent depth.

Terra:
- #453 content-identity fix in new worktree/PR.
- Recover #406 partial work after inspecting old local branches/worktrees.
  Issue concerns unbounded shared streams filling disk/pinning I/O.
  Owner decisions about share confirmation and size cap remain unresolved;
  implement only justified safe portion, preserve input validation.
- Continue remaining software issues after fresh triage.

Luna:
- Read-only issue criterion inventories.
- Verify #348 localization gaps after merged fixes.
- Check #331 notification-run evidence and missing device checks.
- Cleanup inventory of old merged clean worktrees.
- Targeted fast review, but concurrency/security approval requires Sol or Terra
  with enough scope and evidence.

Do not launch ten simultaneous builds. Agents can inspect/edit in parallel;
one local Gradle slot remains the default.

PENDING DEVICE/EVIDENCE TASKS
#331:
- #439 channel/text test and #446 posted-notification test merged.
- Actual posted-notification/cold-start run was prepared but not proved complete.
- Script:
  /Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/publication/walk-ci/plan-331/run-331.sh
- Last known SHA:
  e71197bb3009aea9cdb8ec92b28104075c86e9ece36c647a8eebc335ec92c23a
- Inspect before use. Do not assume owner/port hardening matches newer script.
- Real system locale attempt falls back to app-locale stand-in if unavailable;
  report that limitation honestly.
- Test reads actual posted notification and independently derived expectations.
- Cold-start keep steps must read stored pt-BR/en, not reset it between phases.

#319:
- Long audio normalization timing evidence incomplete.
- Predeclared plan and analyzer:
  verification-logs/publication/walk-ci/plan-319-linearity/
  under external evidence root below.
- Driver:
  driver.sh <debug.apk> <androidTest.apk> <outdir>
- Plan hash:
  86a521d0867538ab739711e013a39c66555a5f30e3fef6bb70ff584ada72b49c
- Analyzer hash:
  fd8337b0bc9c3a8eb326b0de5184eb363cc6aebb29f81a6a3f90643419a71708
- Driver hash:
  e5db45658c39ebf2383d20983ba96fd2643c601155582ffb42bd58fa1a4eb309
- Three passes of15/30/60min preencoded synthetic stereo M4A.
- Sampler off; heap evidence separate.
- Valid pass requires host1min load<=12 at start AND end.
- Fewer than2 valid passes => INCONCLUSIVE.
- r_L=median normalizeMs/audioMinute for each length.
- PASS only max(r)/min(r)<=1.5 and each r<=20000ms/min.
- Never relax thresholds after seeing numbers.
- This load gate applies to timing experiment, NOT all project work.
- #444 heap-not-measured status line merged.

#103:
- Test-only evidence PR #448 merged.
- Real local regeneration with network disabled still needs real local LLM.
- Real API regeneration needs owner credentials.
- Existing fake-engine walks are not real-engine proof.

#107:
- Independent audit rounds continue; do not declare ship-ready with blockers.

BLOCKERS AND OWNER DECISIONS
Hardware:
- #3–#9 Note identity/BLE/resume/CRC/Opus/SoftAP/FE10.
- Owner has Note Plus, not confirmed as substitute for Note Pro.
- #12 performance/thermals; #13 low-RAM device constraint; #14 real meetings.
- #15–#17 other recorder hardware.
- #35/#36 physical iPhone, Keychain and real local-AI verification.
- #85 download foreground-service survival, #86 final hardware criterion.
- #323 real calendar/device timing.
- Do not substitute synthetic evidence for these.

Credentials/cloud:
- #26 Play signing/account and console configuration.
- #37 Apple Developer/TestFlight/signing.
- #49–#59, #61 integrations.
- #67, #78–#82 managed/cloud services.
- #103 real API half.
- Do not create accounts or credentials.

Decisions:
- #29 desktop go/no-go gates desktop/sync work.
- #74 cloud product definition blocks #75/#76; no placeholder cloud interfaces.
- #71 voice profiles in encrypted bundle.
- #91 APK target/bundling and forbidden .github changes.
- #94 remaining acceptance/debug-driver decisions.
- #96 mock-vs-real network criterion split with #8.
- #28 fixed SoftAP nonce/security decision and dependency bot.
- #204 tag filter control.
- #160 original timeout signature vs continued soak acceptance.
- #311 non-reproduced detach-lifecycle closure decision.
- #18 Limitless protocol no-go decision.
- #357 Wi-Fi early-close fallback acceptance.
- #432 Soundcore driver go/no-go.
- #395 importing a file whose row is trashed.
- #212 wrong-key complete partial deletion policy.
- #406 share confirmation and size cap.
- #98 CI benchmark job and standard Whisper selection.
- #405 pending-share24h/three-start retention was implemented but owner has
  not reviewed exact retention policy.
- Closing old PRs #315/#351/#356/#359/#372 requires owner direction.
- Branch deletion remains forbidden without explicit direction.

STORE READINESS
Not ready to publish. Do not promise dates without evidence.

Play:
- Release config/listing assets exist.
- Upload key, console declarations/data safety/rating, signed AAB/internal-track
  installation and real hardware gates remain.

F-Droid #27:
- Audit comment already posted.
- Checked-in libvelum_needle.so and libc++_shared.so are not automatically
  proprietary, but source provenance/reproducibility unproved.
- ai/src/androidMain/cpp/CMakeLists.txt builds JNI wrapper around libneedle.a.
- scripts/fetch-needle-android.sh uses mutable Hugging Face resolve/main with
  no pinned digest.
- Need engine source revision/toolchain and dependency/license audit.
- sherpa-onnx, kopus, LiteRT-LM transitive native/source licenses need evidence.
- Model licenses separate from runtime binaries; SenseVoice label unresolved.
- metadata/dev.velum.app.yml is draft referencing v1.0.0.
- Never create tag to make draft metadata look complete.
- Submission/acceptance remain owner/external.

iOS:
- Host exists and simulator compile is not physical-device validation.
- Keychain persistence, encryption of profiles/embeddings, recording/import/AI,
  signing and TestFlight remain gates.

SKILLS AND ENGINEERING STYLE
Owner requests using applicable skills/framework guidance:
- no-ai-slop, unslop, Visant skills.
- security-audit skill.
- Effect-TS when appropriate to TypeScript, not forced into Kotlin.
- unlazy, caveman, ponytail, pstack, goldie, humanizer, stop-slop.
Load available applicable skills; do not silently install dependencies/hooks.
Use the simplest correct existing pattern; avoid speculative abstractions.
Unlazy: explicit acceptance gates and evidence, no placeholders or fake completion.
Normal prose in code/docs/PRs; concise user updates.
Current user conversation is mostly Portuguese; match current user language.

KOTLIN STRINGS LIMIT
UxStrings has245 constructor fields, exactly JVM limit.
246 fails with ClassFormatError “Too many arguments in method signature”.
New strings go into MoreStrings / Strings.added, guarded by StringsSizeTest.
Do not trust stale MEMORY.md text claiming246 is valid.

EVIDENCE AND RECOVERY LOCATIONS
External evidence root:
  /Users/heitor/Documents/Codex/2026-10-31/task-2/verification-logs/

Old triage:
  /private/tmp/claude-501/-Users-heitor-Developer-github-com-bloodf-velum/2b6d6db5-3864-43a7-89f9-3f7ee3c9e104/scratchpad/open-issues-triage.md
It is stale; reconcile with GitHub.

Old delete recovery:
  /Users/heitor/Documents/Codex/2026-11-04/recovery-delete-387
At74543e7 with an uncommitted isDeleting helper when reassigned.
Do not delete until new work fully preserved.

Original unpublished delete work:
  /Users/heitor/Documents/Codex/2026-11-01/wt-delete-rest-387
Commit cb5cc763, superseded selectively; old failure XML might be mutation output,
not evidence committed code failed or passed.

Original import work:
  /Users/heitor/Documents/Codex/2026-11-03/wt-318
Local4d7612c3, old branch codex/imports-318.
Recovered branch is already merged; inspect before cleanup.

PR451 executor:
  ae81011e01b9c1191
PR451 reviewer:
  ac6ee6f19f5db08f3
Import executor:
  ad49be5762ffda7e0
Import reviewer:
  aa4384daffcaf1f68
Enqueue executor:
  ae7a640a40fefb600
Enqueue reviewer:
  ab9b4dc8d197dbdb1

These are historical agent IDs, not portable t3 handles.
Do not assume old agents or processes are alive.
Last attempt to resume #451 executor said stopped by user and not resumable.
Start fresh t3 agents explicitly with preserved worktree evidence.

FIRST EXECUTION CHECKLIST
1. Read repository/global instructions.
2. Verify cwd/status/main/open issues/PR heads and live processes.
3. Configure requested Astra/Sol/Terra/Luna routing using actual available IDs.
4. Establish one build-slot owner; avoid overlapping compilation.
5. Sol: recover final #451 compile evidence at451944db; rerun if unknown.
6. Independent review already approved exact451944db statically; inspect report
   and re-review any further code delta.
7. Push #451 normally once executable gates proved; green CI then merge.
8. Terra: implement #453 separately with deterministic content-change test.
9. Luna: inventory cleanup candidates and unresolved software criterion gaps.
10. After merge batch, run fresh complete main regression.
11. Close issues only with criterion evidence.
12. Keep loop active and report blockers when list changes.
13. Never claim store launch readiness while hardware/signing/privacy/platform
    gates remain open.

Proceed autonomously. Do not reimplement already preserved work.
