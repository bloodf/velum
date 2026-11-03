# Local model benchmark (#98)

One entry point, `dev.velum.app.bench.LocalModelBenchmarkDeviceTest`, runs **real** on-device inference
(no mocked model calls) through the shipping paths (`LocalTranscriber`, `LocalLlmEngine`) and writes a JSON report.
Hardware evidence for phones stays in #12; **emulator numbers are not phone-performance or quality evidence**.

## Prerequisites
Each model must already be Ready in the app's model store (download it in Model manager on that device/emulator).
A model that is not Ready, throws, or returns empty output fails the run; there is no partial pass.

## Run (emulator or a connected phone, same command)
```
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest   # or the minifiedTest variants
adb install -r composeApp/build/outputs/apk/debug/*.apk
adb install -r composeApp/build/outputs/apk/androidTest/debug/*.apk
adb shell am instrument -w \
  -e class dev.velum.app.bench.LocalModelBenchmarkDeviceTest \
  -e additionalTestOutputDir /sdcard/Android/media/dev.velum.app.test/bench \
  -e bench.models parakeet-tdt-0.6b-v3-int8,whisper-large-v3-turbo,whisper-tiny,gemma-4-e4b \
  -e bench.workloadSeconds 60 \
  dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
adb pull /sdcard/Android/media/dev.velum.app.test/bench/bench-report.json
```
The report is also logged under tag `VelumBench98`. Models can be any ids from `DefaultLocalCatalog`
(the catalog has `whisper-large-v3-turbo` and `whisper-tiny`; use `whisper-tiny` as the "standard" Whisper
unless the owner names another model). Ids starting `gemma`/`qwen` run the summary workload, others run ASR.

## Workloads
- `bench.workloadSeconds` = source audio duration (default 60). The fixed input (default: bundled `device_walk_tone.wav`,
  or `-e bench.audio /sdcard/x.wav`, 16 kHz mono PCM16 WAV, 44-byte header) is looped to that length.
- **1 h workload for #12:** `-e bench.workloadSeconds 3600 -e bench.audio <non-sensitive speech wav>` on the physical phone.
  Run it once per model, plugged in, screen on; battery, temperature, throttling and screen-off checks are #12.
- Gemma/Qwen summarize one fixed transcript (in the test source, about 90 words), which stands for a short meeting; its
  `inputDurationMs` is `bench.workloadSeconds`, so state the real audio length when quoting its RTF.

## Definitions
- **elapsed**: wall-clock of the single `transcribe`/`complete` call (model load inside the call is included
  by the first call; the engines keep no warm state across models).
- **RTF** = elapsed / source audio duration (`ai/.../bench/BenchmarkReport.kt`, covered by `BenchmarkReportTest`).
- **peak RAM**: process peak resident set (`VmHWM` in `/proc/self/status`), reset before each model with
  `clear_refs=5`. It is process-wide, so it includes the app and test process, not only the model.
- Report fields: device, Android release/SDK, ABI, emulator flag, models, workload, audio source, core count.

## CI
The pure parts (RTF, required fields, failure on missing data) run in `:ai:jvmTest`. Running the models in CI needs the
model downloads (about 1.5 GB) on the emulator job and an artifact upload step, i.e. a `.github` change that is not in this PR.
