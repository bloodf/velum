# Issue #98 source coverage inventory

Audit date: 2026-11-06  
Audited HEAD: `963663004e44f7a9b3816fc4c247a8053a2f6578` (`t3/android-benchmark-coverage`; same commit as `origin/main`)  
Scope: read-only source and test inventory. No build, test, inference, emulator, download, or benchmark was run.

## Source of requirements and limits

`docs/BENCHMARK.md` identifies itself as the local model benchmark for #98 and describes the supported workloads and acceptance procedure. GitHub issue #98 could not be independently fetched: both the web page and `gh issue view` failed because GitHub was unreachable. This mapping therefore covers the requirements explicitly documented in the checked-out source, not any issue-body text absent from the repository.

The required CodeGraph CLI is pinned at 1.6.2, but `codegraph status --json` reported `initialized:false`. The repository guide's `init`/`sync` operations would write index state, so they were not run under the no-file-modifications constraint. Bounded `rg` and source-range inspection were used instead. The worktree was clean before creating this requested artifact; afterward, its only change is this untracked report.

## Requirement-to-source/test mapping

| Documented requirement | Current source | Existing regression coverage | Finding |
|---|---|---|---|
| Invoke shipping local ASR and LLM engines, with no fake model calls; fail for unavailable model or empty result | `LocalModelBenchmarkDeviceTest.kt:53-76,79-99`; docs lines 3-12 | No test of this instrumented benchmark was run or found by the bounded benchmark-file search; JVM `LocalLlmResolutionTest` covers model resolution, not real engine inference. | Implemented in source. Real runtime/model behavior is device proof pending. |
| ASR workload takes 16 kHz mono PCM16 speech, windows to requested duration, records elapsed, output chars and peak RSS | device test lines 79-90,123-160; docs lines 40-58 | `WindowedAsrTest` covers window calculations; `BenchmarkReportTest` covers formula and report data only. No benchmark-specific WAV parser test found. | Source-complete for the documented normal-input path; parser/device execution not proven here. |
| LLM workload summarizes a fixed transcript and identifies the exact transcript input | device test lines 92-99,168-176; environment/hash at 101-118 | `BenchmarkReportTest.kt:49-55` checks summary hash requirement; no real LiteRT inference regression in the JVM test. | Input and reporting are present; on-device inference and output quality remain unproven. |
| Report is reproducible and complete: environment, measurements, positive values, output, and SHA-256 inputs; RTF is elapsed/source duration | `BenchmarkReport.kt:17-48,50-82`; device test lines 72-76,101-118 | `BenchmarkReportTest.kt:17-62` covers RTF, JSON fields, blank/empty requests, hashes and required measurement fields. | Covered at report-model layer. Tests do not validate actual measurement accuracy. |
| Prevent stale report on failed run; write only validated report; identify instrumented success from output and pulled artifact | device test lines 53-76; docs lines 23-34 | No regression test for file deletion/write failure or instrumentation output parsing found. | Procedural/documented acceptance gate; not regression-tested in checked-in JVM suite. |
| Separate emulator evidence from phone evidence; physical-phone long workload and thermals/battery/screen-off are tracked elsewhere (#12) | docs lines 3-5,44-45,53-58; `STATUS.md:162` | No source test can establish phone performance or quality. | Explicitly hardware-only proof pending. Emulator results must not be treated as phone performance/quality evidence. |

## Concrete source coverage gap

`BenchReport.requireValid` verifies that every requested model ID appears at least once, but it does not reject duplicate result rows or extra, unrequested model IDs (`BenchmarkReport.kt:57-69`). Current production construction maps each requested ID once (`LocalModelBenchmarkDeviceTest.kt:63-71`), so this does not invalidate that path; it means the report validator alone does not enforce a one-to-one requested-model/result invariant. Existing `BenchmarkReportTest.valid_report_passes` checks two requested IDs, but there is no regression case for duplicates or unexpected IDs. This is a narrow validator coverage gap, not evidence of a benchmark failure.

The WAV parser is private to the instrumented test and has no targeted test cases in the located benchmark test set. The source enforces format metadata and nonempty data for ordinary files, but malformed/truncated RIFF chunk bounds are not independently regression-tested. No failure or bug is asserted from that absence.

## Device acceptance gate (documented, not performed)

`docs/BENCHMARK.md:14-34` provides the explicit serial-bound device procedure: install debug and Android-test APKs, place a speech WAV in app-specific external storage, invoke `LocalModelBenchmarkDeviceTest`, inspect instrumentation output (`OK (1 test)`), and pull `bench-report.json`. It warns that `am instrument` can return zero on test failure and that absence of the report is not success. The model must already be Ready on the device. For #12, docs lines 44-45 specify a physical phone, plugged in, screen on, 3600-second workload per model; battery, temperature, throttling and screen-off checks are separate acceptance concerns.

None of those device gates were attempted. Source/tests provide no proof of successful inference on a phone, model readiness on hardware, thermal behavior, battery impact, throttling, or quality. The current defensible conclusion is: **the documented harness and core report regression tests are present; documented device proof remains pending**.

## Proposed narrow checks (not run)

Pure report regression command:

```sh
./gradlew :ai:jvmTest --tests 'dev.velum.ai.bench.BenchmarkReportTest'
```

Documented benchmark device entry point, only when device execution is separately authorized and prerequisites are already satisfied:

```sh
adb -s "$S" shell am instrument -w \
  -e class dev.velum.app.bench.LocalModelBenchmarkDeviceTest \
  -e bench.audio /sdcard/Android/data/dev.velum.app/files/bench.wav \
  -e bench.models whisper-tiny \
  -e bench.workloadSeconds 60 \
  dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
```

The second command performs real inference and was recorded only as the documented acceptance invocation; it was not run in this audit.
