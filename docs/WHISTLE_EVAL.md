# Whistle (Cactus Compute) for Velum

Sources: [Cactus blog](https://cactuscompute.com/blog/whistle), [HF model card](https://huggingface.co/Cactus-Compute/whistle), [Needle engine](https://huggingface.co/Cactus-Compute/needle3), [ESP32 demo](https://github.com/andrisgauracs/whistle-esp32). Updated 2026-10-27.

## Verdict: **adopted as low-end engine for Whistle languages**

Heitor clarified Velum does **not** need every language, but must cover the **important** set below. Whistle (Apache-2.0, **16.9 MB**) is the **low-end / Tiny** engine when the detected or selected language is one of **en, de, fr, es, it, nl, pl**. Portuguese and the other important languages use **Whisper Tiny (sherpa-onnx)** on low-end devices. Mid/high devices keep Parakeet / SenseVoice / Whisper turbo via the existing router.

## Important languages (product minimum)

Velum’s local ASR path must cover at least:

| Code | Notes |
|---|---|
| **pt** | Portuguese (incl. pt-BR) — **Whisper Tiny** / Parakeet on mid+ |
| **en, es, fr, de, it, nl, pl** | Whistle on low-end; Parakeet on mid+ |
| **ja, zh, ko** | SenseVoice (mid+) / Whisper Tiny (low-end) |
| **ru, ar, hi, tr** | Whisper turbo (mid+/quality) / Whisper Tiny (low-end) |

Defined in code as `ImportantLanguages.codes` (`ai/.../LocalModels.kt`).

## Facts

| | |
|---|---|
| Size | **16.9 MB** (`whistle.cact`) |
| SHA-256 | `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb` |
| License (weights) | **Apache-2.0** |
| Languages | **en, de, fr, es, it, nl, pl** — **no pt** |
| Runtime | Needle C API (`needle_load` / `needle_transcribe`) via Velum JNI `libvelum_needle.so` |
| Engine license | Needle3 Android static libs on HF — **Apache-2.0** |
| Download | Hugging Face `Cactus-Compute/whistle` → SHA-verified atomic write under `models/whistle/` |

## Comparison vs Velum ASR catalog

| Model | Size (catalog) | Languages | Runtime | PT? | Role in Velum |
|---|---|---|---|---|---|
| **Whistle** | 16.9 MB | 7 EU | Needle JNI `.cact` | **No** | **Low-end / Tiny when language ∈ Whistle set** |
| **Whisper Tiny** (int8) | ~111 MB archive | ~99 | sherpa-onnx | **Yes** | **Low-end / Tiny for pt + other majors** |
| SenseVoice Small | ~156 MB | zh/yue/en/ja/ko | sherpa-onnx | No | CJK light (mid+) |
| Parakeet TDT 0.6B v3 | ~465 MB | 25 EU incl. PT | sherpa-onnx | Yes | Default standard |
| Whisper large-v3-turbo | ~538 MB | ~99 | sherpa-onnx | Yes | Quality / non-EU fallback |

## Integration (implemented)

1. Catalog entry `whistle` with HF URL + pinned SHA-256; non-archive `.cact` readiness (`ModelLayout.SingleCactFile`).
2. Android JNI: `ai/src/androidMain/cpp/velum_needle_jni.cpp` + packaged `jniLibs/{arm64-v8a,armeabi-v7a}/libvelum_needle.so` (links Needle `libneedle.a`) and `libc++_shared.so`.
3. `NeedleWhistleEngine` behind `LocalAsrRuntime.transcribe` when `modelId == whistle`.
4. `LocalAsrRouter.chooseLowEndModelId(lang)` — Whistle vs Whisper Tiny.
5. Settings **Auto / Tiny / Standard / Quality** remain; Tiny clears an explicit model pin so the language router can choose.

Rebuild natives: `scripts/fetch-needle-android.sh` then CMake under `ai/src/androidMain/cpp/` (NDK 27+).

## Low-end selection policy

- **Auto + low RAM:** language ∈ Whistle → `whistle`; else → `whisper-tiny`.
- **Tiny (manual):** same language split (does not force Whisper Tiny for English, etc.).
- **Standard / Quality:** unchanged Parakeet / SenseVoice / Whisper turbo language defaults.
- **Explicit catalog pin** in Model manager still wins over the tier router.

## Hardware verification

| Claim | Status |
|---|---|
| Router unit tests (Whistle vs Tiny by language) | **Verified** in JVM CI |
| SHA of `whistle.cact` matches catalog | **Verified** (downloaded + hashed in research) |
| `libvelum_needle.so` links Needle symbols | **Verified** (NDK link of `needle_load` / `needle_transcribe`) |
| End-to-end Whistle transcription on a physical phone | **Not verified in this environment** (no device); needs arm64 APK + downloaded `.cact` |
| x86/x86_64 emulator Whistle | **Not supported** (ABI filters arm only) — use Whisper Tiny / API on emulators |

## Why not Whistle-only on low-end

Portuguese is an important language and Whistle cannot transcribe it. Dual low-end engines (Needle + sherpa) are intentional: smallest possible weights when Whistle fits; Whisper Tiny otherwise.

## Measuring Whistle on an emulator and a phone (#13)

Harness: `WhistleMeasureDeviceTest` (androidInstrumentedTest). It checks `whistle.cact` against the catalog SHA-256,
runs one cold transcription and three warm ones on a speech clip, and prints one `velum.whistle` status line: device,
Android release, ABI, app version and revision, model and clip SHA-256, audio seconds, cold ms, warm median ms,
estimated load ms, RTF, memory, and the transcript. **A run without the `velum.whistle` line measured nothing**
(the test also fails, naming the path, if an argument is given but its file is missing or unreadable).

Definitions:
- **loadMs (estimate)**: cold call minus warm median. It is the difference of two timed transcriptions, not a timed
  model load, so it is only meaningful with a short clip (10 to 30 s); a negative value means the estimate failed.
- **RTF**: warm median transcribe seconds / audio seconds (below 1 is faster than real time).
- **Memory**: `VmRSS` and `VmHWM` before the first call and `VmHWM` after the last call, from `/proc/self/status`.
  This is the whole test process (JVM and app included), not Whistle alone.

Inputs: the model is `whistle.cact` from Hugging Face `Cactus-Compute/whistle` (see the table above; the test fails
on a SHA mismatch). The clip is any short 16 kHz mono 16-bit PCM WAV with real speech in an English/EU language the
model supports, e.g. exported from your own recording with `ffmpeg -i in.m4a -ar 16000 -ac 1 -c:a pcm_s16le speech16k.wav`;
its SHA-256 is printed so both environments can be shown to use the same file. The device-walk tone is not speech.

1. Build and install the arm64 debug APK and test APK (`./gradlew :composeApp:installDebug :composeApp:installDebugAndroidTest`).
2. `adb push whistle.cact /data/local/tmp/ && adb push speech16k.wav /data/local/tmp/`
3. Run, and keep the `INSTRUMENTATION_STATUS: velum.whistle=...` line (no line, no measurement):
   `adb shell am instrument -w -e class dev.velum.app.devicewalk.WhistleMeasureDeviceTest -e velumDeviceWalk false -e whistleCact /data/local/tmp/whistle.cact -e whistleWav /data/local/tmp/speech16k.wav -e appRevision $(git rev-parse HEAD) dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner`
4. Emulator: an arm64-v8a system image on an Apple-silicon host, `hw.ramSize=3072` (or 4096) in the AVD `config.ini`,
   and `-cores 2`. **That does not satisfy the "throttled CPU" criterion**: the Android emulator cannot lower the CPU
   clock, so `-cores 2` limits parallelism only. Record RAM and core count as the constraint, state that the clock was
   not throttled, and do not call the result a low-end phone. If the criterion must be met literally, it needs a
   throttling method that actually lowers the clock (not available in the stock emulator) and is blocked until one is chosen.
5. In-app check ("display resulting transcript"): the harness proves the engine returns text, not that the app shows
   it. On the same device, install the debug APK, download the Whistle model in Model manager, import or record the
   same clip with an English language setting, process it, and confirm the transcript is displayed in the recording
   detail without a crash; note the screenshot or the visible text. Do this on both environments.
6. Record the same run on the physical phone (model identity, Android version, `git rev-parse HEAD` of the build, clip duration).

Results to commit under this heading, each labelled emulator or physical: pending. Constrained-emulator numbers do not
establish real low-end phone performance; low-end phone feedback remains community validation, not an owner hardware purchase.
