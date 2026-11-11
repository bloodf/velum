# Local model benchmark (#98)

One entry point, `dev.velum.app.bench.LocalModelBenchmarkDeviceTest`, runs **real** on-device inference
(no mocked model calls) through the shipping engines (`LocalTranscriber`, `LocalLlmEngine`) and writes a JSON report.
Hardware evidence for phones stays in #12; **Actual model inference is permitted only on physical devices.**

## Prerequisites
- Each model must already be Ready in the app's model store (`filesDir/models`; download it in Model manager on that
  physical device). A model that is not Ready, throws, or returns empty output fails the run; there is no partial pass.
- A speech WAV, **16 kHz mono PCM16**, non-sensitive, pushed to the device. There is no bundled input: the engines reject
  silence and tones with an empty-text error. Any short public-domain speech clip works; sherpa-onnx model archives ship
  `test_wavs/`. The test rejects any other format.

## Run (physical Android phone only)
Always pass the serial explicitly (`adb devices -l`): a bare `adb` can select the wrong device. Never run model inference on an emulator.
```
S=<serial>
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
adb -s $S install -r composeApp/build/outputs/apk/debug/*.apk
adb -s $S install -r composeApp/build/outputs/apk/androidTest/debug/*.apk
adb -s $S shell mkdir -p /sdcard/Android/data/dev.velum.app/files
adb -s $S push speech.wav /sdcard/Android/data/dev.velum.app/files/bench.wav
adb -s $S shell am instrument -w \
  -e class dev.velum.app.bench.LocalModelBenchmarkDeviceTest \
  -e bench.audio /sdcard/Android/data/dev.velum.app/files/bench.wav \
  -e bench.fixtureSensitivity non-sensitive \
  -e bench.models whisper-tiny \
  -e bench.workloadSeconds 60 \
  dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
adb -s $S pull /sdcard/Android/data/dev.velum.app/files/bench-report.json
```
`am instrument` can exit 0 even when a test failed. Read its output: success is `OK (1 test)`; `FAILURES!!!` or
`INSTRUMENTATION_FAILED` is a failed run, and no report counts as a result. The report is also logged under tag
`VelumBench98`, The old `bench-report.json` is deleted when a run starts and the new one is written only after validation, so a failed run leaves no stale file; a failed write fails the run.
The app-specific directory `/sdcard/Android/data/dev.velum.app/files/` is used because the app process has no storage permission for `/sdcard/Download`. Create it before the push if it does not exist yet (`mkdir -p` below).

Models are any ids in `DefaultLocalCatalog`. ASR models (`LocalModelKind.Asr`) run the transcription workload and LLM
models (`LocalModelKind.Llm`) the summary workload; other kinds are rejected. The catalog has `whisper-large-v3-turbo`
and `whisper-tiny`; `whisper-tiny` is the default "standard" Whisper until the owner names another model.

## Workloads
- `bench.workloadSeconds` = source audio duration (default 60). The clip is looped to that length and run through the
  shipping windowed ASR path (30 s windows, 28 s hop, one `transcribe` per window, each window built on demand so the
  workload is never fully in memory).
- **1 h workload for #12:** `-e bench.workloadSeconds 3600` on the physical phone, plugged in, screen on, once per model.
  Battery, temperature, throttling and screen-off checks are #12.
- LLM models summarize one fixed transcript (84 words, in the test source). It stands for 34 s of speech (84 words at an assumed
  150 words per minute), recorded as `inputDurationMs` = 34,000 and `summaryTranscriptSourceSeconds`; RTF for Gemma is
  elapsed divided by that.

## Definitions
- **elapsed & time boundaries**: sum of wall-clock time spent inside the engine calls (`timeBoundary: windowed_asr_engine_transcribe_wall_clock_sum` for ASR, `timeBoundary: single_complete_call_wall_clock` for summary). Audio slice allocation and engine wrapper instantiation occur outside timed blocks. The runtime builds its recognizer inside each call, so **model load is included in every call**, as in the app.
- Not included: the app's thermal gate (`thermal.awaitCool` pauses in `RecordingProcessor`) is not part of this harness, so a hot device shows up as slower elapsed time, not as a pause.
- **RTF & units**: `rtfUnit: ratio_elapsed_over_source_duration`, defined as elapsed time divided by source audio duration (`ai/.../bench/BenchmarkReport.kt`, serialized in the report as `rtf`, covered by `BenchmarkReportTest`).
- **peak RAM method**: `peakRamMethod: proc_self_clear_refs_5_vmhwm`. Process peak resident set (`VmHWM` in `/proc/self/status`), reset before each model by writing `5` to `/proc/self/clear_refs`; the run fails closed if the reset does not take within tolerance (`HWM_TOLERANCE_KB = 4096 KB`). It is process-wide (app and test): app startup memory still resident at the reset is part of the first model's peak, so run a small warm-up model first or compare later models.
- **Model runtime & fixture metadata**: Every result records `runtime` (`local-asr`, `local-llm`), and the environment records hardware/OS details (`device`, `androidRelease`, `sdkInt`, `abi`, `isEmulator: false`, `cores`), exact fixture format (`audioFormat: PCM16_MONO_16KHZ`), explicit sensitivity declaration (`fixtureSensitivity: non-sensitive`), and the SHA-256 of the exact PCM clip (`audioSha256`) and fixed transcript (`summaryTranscriptSha256`). A run is only reproducible with the same hashes and settings; `requireValid()` rejects reports with missing or invalid metadata.
- **Measurement discipline**: In adherence to the benchmark checklist, never report simulated, mocked, or unmeasured numbers. No emulator inference is allowed.

## CI
The pure parts (RTF calculation, schema validation, required metadata fields, and fail-closed rejections) run in `:ai:jvmTest`. Actual model inference is strictly prohibited on emulators and emulator CI.

## Report scope and provenance limits

Reports use `schemaVersion: 2`. Runtime and time-boundary fields must match the task; omitted values fail validation. The caller must explicitly pass `bench.fixtureSensitivity=non-sensitive` after checking the external speech fixture. This declaration is not an automatic content or privacy audit. Audio hashes identify the decoded PCM payload, not the WAV container.

The engine identifiers describe the application wrappers, not a native library version or backend configuration. Model IDs do not prove the installed artifact hash. Artifact hashes, native runtime versions, build fingerprints and detailed hardware identity remain follow-up work for #98. These schema tests and source changes provide no measured performance or physical-device acceptance.
