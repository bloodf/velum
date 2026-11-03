# Local model benchmark (#98)

One entry point, `dev.velum.app.bench.LocalModelBenchmarkDeviceTest`, runs **real** on-device inference
(no mocked model calls) through the shipping engines (`LocalTranscriber`, `LocalLlmEngine`) and writes a JSON report.
Hardware evidence for phones stays in #12; **emulator numbers are not phone-performance or quality evidence**.

## Prerequisites
- Each model must already be Ready in the app's model store (`filesDir/models`; download it in Model manager on that
  device/emulator). A model that is not Ready, throws, or returns empty output fails the run; there is no partial pass.
- A speech WAV, **16 kHz mono PCM16**, non-sensitive, pushed to the device. There is no bundled input: the engines reject
  silence and tones with an empty-text error. Any short public-domain speech clip works; sherpa-onnx model archives ship
  `test_wavs/`. The test rejects any other format.

## Run (emulator or a connected phone, same command)
Always pass the serial explicitly (`adb devices -l`): with an emulator and a phone attached, a bare `adb` is ambiguous.
```
S=<serial>
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
adb -s $S install -r composeApp/build/outputs/apk/debug/*.apk
adb -s $S install -r composeApp/build/outputs/apk/androidTest/debug/*.apk
adb -s $S push speech.wav /sdcard/Download/bench.wav
adb -s $S shell am instrument -w \
  -e class dev.velum.app.bench.LocalModelBenchmarkDeviceTest \
  -e bench.audio /sdcard/Download/bench.wav \
  -e additionalTestOutputDir /sdcard/Android/media/dev.velum.app.test/bench \
  -e bench.models whisper-tiny \
  -e bench.workloadSeconds 60 \
  dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
adb -s $S pull /sdcard/Android/media/dev.velum.app.test/bench/bench-report.json
```
`am instrument` can exit 0 even when a test failed. Read its output: success is `OK (1 test)`; `FAILURES!!!` or
`INSTRUMENTATION_FAILED` is a failed run, and no report counts as a result. The report is also logged under tag
`VelumBench98`, so a failed file write loses nothing.

Models are any ids in `DefaultLocalCatalog`. ASR models (`LocalModelKind.Asr`) run the transcription workload and LLM
models (`LocalModelKind.Llm`) the summary workload; other kinds are rejected. The catalog has `whisper-large-v3-turbo`
and `whisper-tiny`; `whisper-tiny` is the default "standard" Whisper until the owner names another model.

## Workloads
- `bench.workloadSeconds` = source audio duration (default 60). The clip is looped to that length and run through the
  shipping windowed ASR path (30 s windows, 28 s hop, one `transcribe` per window, each window built on demand so the
  workload is never fully in memory).
- **1 h workload for #12:** `-e bench.workloadSeconds 3600` on the physical phone, plugged in, screen on, once per model.
  Battery, temperature, throttling and screen-off checks are #12.
- LLM models summarize one fixed transcript (about 90 words, in the test source). It stands for 36 s of speech (an assumed
  150 words per minute), recorded as `inputDurationMs` = 36,000 and `summaryTranscriptSourceSeconds`; RTF for Gemma is
  elapsed divided by that.

## Definitions
- **elapsed**: sum of the wall-clock of the engine calls (one per ASR window, one for the summary). The runtime builds its
  recognizer inside each call, so **model load is included in every call**, as in the app.
- **RTF** = elapsed / source audio duration (`ai/.../bench/BenchmarkReport.kt`, serialized in the report as `rtf`, covered
  by `BenchmarkReportTest`).
- **peak RAM**: process peak resident set (`VmHWM` in `/proc/self/status`), reset before each model by writing `5` to
  `/proc/self/clear_refs`; the run fails if the reset does not take. It is process-wide (app and test), not only the model.
- Report fields: device, Android release/SDK, ABI, emulator flag, models, workload, audio path, window sizes, core count.

## CI
The pure parts (RTF, required fields, failure on missing data) run in `:ai:jvmTest`. Running the models in CI needs the
model downloads (about 1.5 GB) on the emulator job, a speech fixture, and an artifact upload step, i.e. a `.github`
change that is not in this PR.
