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
- **elapsed**: sum of the wall-clock of the engine calls (one per ASR window, one for the summary). The runtime builds its
  recognizer inside each call, so **model load is included in every call**, as in the app.
- Not included: the app's thermal gate (`thermal.awaitCool` pauses in `RecordingProcessor`) is not part of this harness, so a hot device shows up as slower elapsed time, not as a pause.
- **RTF** = elapsed / source audio duration (`ai/.../bench/BenchmarkReport.kt`, serialized in the report as `rtf`, covered
  by `BenchmarkReportTest`).
- **peak RAM**: process peak resident set (`VmHWM` in `/proc/self/status`), reset before each model by writing `5` to
  `/proc/self/clear_refs`; the run fails if the reset does not take. It is process-wide (app and test): app startup memory still resident at the reset is part of the first model's peak, so run a small warm-up model first or compare later models.
- Report fields: device, Android release/SDK, ABI, physical-device flag, models, workload, audio path, window sizes, core count, and the SHA-256 of the exact PCM clip (`audioSha256`) and of the fixed transcript (`summaryTranscriptSha256`). A run is only reproducible with the same hashes; the report is rejected without them.

## CI
The pure parts (RTF, required fields, failure on missing data) run in `:ai:jvmTest`. Actual model inference is not permitted in emulators or emulator CI.
