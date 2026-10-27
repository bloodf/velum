# Whistle (Cactus Compute) evaluation for Velum

Sources: [Cactus blog](https://cactuscompute.com/blog/whistle), [HF model card](https://huggingface.co/Cactus-Compute/whistle), [ESP32 demo](https://github.com/andrisgauracs/whistle-esp32). Evaluated 2026-10-27.

## Verdict: **not adopted as Velum’s low-end default**

Whistle is impressive for microcontrollers and mid-range phones, but it does **not** cover Portuguese and needs a **separate Needle `.cact` runtime** outside Velum’s sherpa-onnx stack. Velum’s low-end tier is therefore **Whisper Tiny multilingual (sherpa-onnx int8, ~111 MB archive)**, which includes **pt** and reuses the existing Android OfflineRecognizer path.

## Facts

| | |
|---|---|
| Size | **16.9 MB** (`whistle.cact`) |
| License | **Apache-2.0** (weights + HF card) |
| Languages | **en, de, fr, es, it, nl, pl** — **no pt / pt-BR** |
| Runtime | Cactus **Needle** C++ engine (`.cact`), not ONNX / TFLite / sherpa |
| Android | Needle ships prebuilt Android binaries; not wired into Velum today |
| ESP32 | Independent C99 port (`whistle-esp32`); proves MCU feasibility, not an Android SDK |
| Accuracy (author) | Ahead of Whisper base on several sets at ~8× smaller; Whisper base ahead on TED-LIUM / AMI / MLS avg |
| Latency (author) | ~11 ms TTFT / ~1.3k tok/s on M4 Pro CPU (not Android SoC) |

## Comparison vs Velum ASR catalog

| Model | Size (catalog) | Languages | Runtime | PT? | Role in Velum |
|---|---|---|---|---|---|
| **Whistle** | 16.9 MB | 7 EU (no PT) | Needle `.cact` | **No** | Documented only; not wired |
| **Whisper Tiny** (int8) | ~111 MB archive | ~99 (Whisper multilingual) | sherpa-onnx | **Yes** | **Tiny / low-end auto tier** |
| SenseVoice Small | ~156 MB | zh/yue/en/ja/ko | sherpa-onnx | No | CJK light |
| Streaming Zipformer EN 20M | ~103 MB | en | sherpa-onnx Online | No | Live EN |
| Parakeet TDT 0.6B v3 | ~465 MB | 25 EU incl. PT | sherpa-onnx | Yes | Default standard |
| Whisper large-v3-turbo | ~538 MB | ~99 | sherpa-onnx | Yes | Quality / non-EU fallback |

## Why not integrate Needle now

1. **Language gap** — primary Velum UX is pt-BR; Whistle cannot transcribe it.
2. **Second native stack** — would add Needle JNI/AAR alongside sherpa-onnx, dual download/verify paths, dual thermal/FGS behavior.
3. **No quality gain for PT users** — even with Apache-2.0 clearance, wrong language set.

If Cactus ships a **PT-capable** Whistle (or Needle loads a PT ladder) later, revisit as an optional catalog entry behind the same SHA-verified download + `localAsrModelId` override.

## Low-end selection policy (implemented)

- **Auto (device RAM/SoC class):** low → `whisper-tiny`; mid → Parakeet (or SenseVoice for CJK); high → language router (Parakeet / SenseVoice / Whisper turbo).
- **Manual override** in AI processing: Auto / Tiny / Standard / Quality (or pin any catalog ASR id).
