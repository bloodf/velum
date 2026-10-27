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
