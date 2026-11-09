# Queued recovery CI diagnosis

Scope: `QueuedRecoveryTest.recovery_keeps_existing_work_even_when_autoflow_template_differs` in PR #479, CI run 42136876607. Source-only; no edits to application or test sources, and no test/build was run.

## Source trace

- `QueuedRecoveryTest.kt` sets up a queued `phone` row and an enabled `phone` AutoFlow whose template is `velum.general` (test lines 255–264).
- Its `beforeSubmit` hook first calls `RecordingProcessWorker.enqueue(..., "velum.meeting", ...)`, then blocks for the operation's result and records that request's UUID (lines 267–276). The outer scheduler calls this hook immediately before submitting recovery's own request (lines 79–92).
- `AppContainer.requeueStrandedRecordings()` checks that the row remains queued, checks `hasActiveWork`, and then calls `enqueueSavedWithAutoflow(..., keepExisting = true)` (AppContainer.kt lines 452–475). It counts the row as handed even if the scheduler reports `KEPT`; the count means handed to scheduling, not newly inserted.
- `RecordingProcessWorker.enqueue()` selects `ExistingWorkPolicy.KEEP` whenever `keepExisting` is true, regardless of template/model tags (RecordingProcessWorker.kt lines 307–330). The scheduler waits for the WorkManager operation result before returning (lines 336–352; 386–397).
- Therefore, by the time recovery's request is submitted, the meeting-template work has already been accepted. Recovery's different `velum.general` template must not replace it. AutoFlow's mismatch is intentional in this test and does not change the recovery policy.

## Diagnosis

The checked-in implementation does not show a source-level mismatch that explains a failure of the final retained-work assertion: the competing request is synchronously accepted before recovery submits, and recovery requests `KEEP`. If CI's failing assertion is the retained UUID/work-info assertion, the likely failure surface is WorkManager test execution/history semantics (for example, the seeded request reaching a terminal state or test database behavior), rather than an AutoFlow race or recovery selecting the wrong template. The test's evidence should distinguish these: capture the original and post-recovery work states/IDs and verify the original remains unfinished at the moment recovery's `KEEP` operation completes.

The test's `handed == 1` assertion is not proof that a second WorkRequest was inserted; the production code intentionally increments for `KEPT`. Its important assertion is that the original UUID is the only retained work item.

I could not fetch the Actions page for run 42136876607 in this environment (web fetch returned a cache miss), so the exact failed assertion/observed WorkInfo state is not independently verified here. The user-reported line 124 does not map to the current checkout's line numbering: the named test begins at line 255. No CI assertion text was available locally.

## Fix direction and regression plan

Do not weaken the assertion to accept replacement. Keep the production `KEEP` policy. If run logs confirm the seeded request is finishing before the assertion, prevent worker execution in this unit test (or use a held constraint/test driver) so the fixture guarantees the competing unique work remains active; then assert the original ID survives the recovery enqueue and remains active. Also assert the first enqueue operation has completed before invoking recovery's submit hook, and report work states on failure. Add/retain a separate case proving an active request with mismatched AutoFlow params survives recovery, plus a control proving ordinary explicit AutoFlow enqueue can still replace mismatched pending work. Re-run the named test and its containing Android unit-test task only after the Linux heavy gate is verified.

## Execution constraints

No test/build was run. The observed `/proc/meminfo` value was `MemAvailable: 11225020 kB`, below the required 12 GiB, so the heavy gate was not satisfied. The permitted process listing exposed only a restricted namespace view; it does not establish the host-global heavy-process count. Remain source-only until that global count and memory gate are verified.
