# Velum: on-device models research (2026-10-26)

Target: Galaxy S26 Ultra / S26+ — Snapdragon 8 Elite Gen 5 for Galaxy (Oryon V3 2x4.74 + 6x3.62 GHz, Adreno 840, Hexagon NPU), **12 GB RAM** (16 GB only on 1 TB Ultra), UFS 4.x.
Sources: https://news.samsung.com/global/samsung-unveils-galaxy-s26-series-the-most-intuitive-galaxy-ai-phone-yet , https://www.gsmarena.com/samsung_galaxy_s26_ultra_5g-14320.php
(S26+ specs not separately verified; assume same SoC class, 12 GB — UNVERIFIED.)

## 1. Speech-to-text
| Model | PT WER (FLEURS, 100 clips, Mac) | Android speed | Size | License |
|---|---|---|---|---|
| **NVIDIA Parakeet-TDT 0.6B v3** (25 EU langs incl. pt) via sherpa-onnx int8 | 3.62% | RTF 0.09 (sherpa-onnx Android, VoicePing; device not stated) | ~640 MB int8 | CC-BY-4.0 (model), Apache-2.0 (sherpa-onnx) |
| **Whisper large-v3-turbo** (809M) | 3.28% | RTF 0.60 sherpa-onnx CPU; QNN/HTP: ~0.8 s per 30 s window + ~12 ms/token (whisper-htp-android, SD 8 Elite-class v79 HTP) | ~1.6 GB fp16 / ~0.5-0.8 GB q5/q8 GGUF | MIT |
| Whisper small/base quantized | worse (not measured here) | fast | 100-250 MB | MIT |
| Moonshine, Kyutai STT, Voxtral Mini, Canary | Moonshine/Kyutai mainly EN(/FR); Voxtral Mini 3B multilingual but heavy for phone; Canary-1B-v2 EU langs (NeMo, less mobile tooling) — no reliable PT phone numbers found | — | — | various |

Sources: https://yapapp.cc/data/on-device-asr-benchmark , https://yapapp.cc/questions/speech-recognition-accuracy-portuguese , https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/ , https://github.com/thedevguy/whisper-htp-android , https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
Caveats: FLEURS is read speech; meeting audio (far-field, crosstalk, code-switching PT/EN) will be much worse. Parakeet v3 has no explicit code-switch mode; Whisper handles language ID per 30 s window. Numbers are third-party, single runs.
Estimate (derived, not measured): 1 h audio with Parakeet at RTF ~0.1 ≈ 6 min; Whisper turbo CPU RTF 0.6 ≈ 36 min, NPU ≈ 120 windows*0.8 s + decode ≈ 3-6 min.

## 2. Diarization
- **sherpa-onnx offline speaker diarization** (Kotlin/JNI, Android APK exists): pyannote segmentation-3.0 (MIT) or Rev reverb-diarization-v1 (check license — Rev's is non-commercial-ish, VERIFY) + embedding 3D-Speaker ERes2Net (Apache-2.0) / NeMo TitaNet-small / WeSpeaker, + clustering. https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/apk.html
- **Voice profiles**: store speaker embeddings (3D-Speaker/WeSpeaker/ECAPA via sherpa-onnx SpeakerEmbeddingExtractor + SpeakerEmbeddingManager) per named person; cosine-match clusters to names.
- NeMo Sortformer (streaming, max 4 speakers) — no mature Android port found; skip for now.
- Diarization speed on phone for 1-2 h not benchmarked publicly — must measure.

## 3. Local LLM (summaries, templates, agents)
LiteRT-LM official table (updated 2026-10-06) https://developers.google.com/edge/litert-lm/overview :
| Model | Size | S26 Ultra CPU prefill/decode | S26 Ultra GPU prefill/decode |
|---|---|---|---|
| **Gemma 4 E2B** | 2.58 GB | 557 / 47 tok/s | 3808 / 52 tok/s |
| **Gemma 4 E4B** | 3.65 GB | 195 / 18 | 1293 / 22 |
| Gemma 3n E2B (S24U) | 2.97 GB | 111 / 16 | 816 / 16 |
| phi-4-mini (S24U) | 3.9 GB | 67 / 7 | 314 / 10 |
| Qwen2.5-1.5B (S25U) | 1.6 GB | 298 / 34 | 1668 / 31 |
- Gemma 4: Apache-2.0, 128K context, 140+ languages trained (good PT). https://ai.google.dev/gemma/docs/core , https://developers.google.com/edge/litert-lm/models/gemma-4
- Qwen3-4B-Instruct-2507: Apache-2.0, 262K native context. https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507 (search snippet claimed ~18.5 tok/s LiteRT-LM GPU on S26 — UNVERIFIED).
- Llama 3.2 1B/3B: Llama community license (not OSI); weaker PT than Gemma 4.
- **1 h transcript (~10-15k tokens)**: Gemma 4 E4B GPU prefill ~1300 tok/s → ~10-12 s prefill + ~500-token summary at ~22 tok/s ≈ 25 s. E2B ≈ 4 s + 10 s. KV-cache RAM at 15-32k context is the real constraint on 12 GB — verify; use map-reduce chunking (per 4-8k tokens) as fallback.
- **Gemini Nano / ML Kit GenAI**: Summarization API exists (beta, 1-3 bullets, **4,000-token input limit**, language list limited), plus Prompt API and Speech Recognition API; built on AICore, device-gated. Too restrictive for templates; optional "quick bullets" only. https://developers.google.com/ml-kit/genai/summarization/android , https://developers.google.com/ml-kit/genai
- Runtimes: **LiteRT-LM** (Kotlin API, CPU/GPU/NPU w/ AOT QNN, official Google; best fit), **llama.cpp** (GGUF any model, CPU + experimental OpenCL/Hexagon; JNI), MLC LLM (Vulkan/OpenCL, smaller ecosystem), ExecuTorch (QNN backend, more engineering), Qualcomm Genie (fastest NPU, proprietary SDK, Qualcomm-only). NPU on Snapdragon needs AOT-compiled models; JIT adds 30-120 s and may fail (https://github.com/lukaskris/litert-lm-mobile-android).

## 4. Recommended stack (Android, KMP)
- Decode Ogg Opus → 16 kHz PCM (MediaCodec / libopus).
- **ASR default**: Parakeet-TDT 0.6B v3 int8 via sherpa-onnx (Kotlin API, Apache-2.0 runtime, fast, PT+EN). **Fallback/"max quality"**: Whisper large-v3-turbo (sherpa-onnx or whisper.cpp; NPU via QNN/AI Hub later). Add Silero VAD (sherpa-onnx) to chunk 1-2 h files.
- **Diarization**: sherpa-onnx pyannote-segmentation-3.0 + 3D-Speaker ERes2Net; voice profiles via embedding store.
- **LLM default**: Gemma 4 E4B (12 GB phones) on LiteRT-LM GPU; **light**: Gemma 4 E2B; alt: Qwen3-4B-2507 GGUF via llama.cpp. JSON/structured output via prompt + validation/retry.
- **Hybrid**: `TranscriptionEngine`/`LlmEngine` interfaces with Local and OpenAICompatible implementations; per-task setting (local default, BYO URL+key optional); models downloaded on demand (not in APK), run in a foreground WorkManager job with thermal monitoring (PowerManager thermal status) and resume checkpoints.
- Caveats: all speeds are vendor/third-party; sustained 1-2 h jobs throttle; storage ~1 GB ASR + ~3.7 GB LLM; check Parakeet CC-BY attribution and pyannote/Rev model licenses; NPU paths are device-specific.

## 5. Update: audio models released Sep-Oct 2026 (checked 2026-10-26)
Verified dated releases:
| Model | Date (source) | What | PT | Size / phone path | License |
|---|---|---|---|---|---|
| **QVAC TranscribePsy-250M-EU / -EU-sub** https://huggingface.co/blog/qvac/transcribepsy-250m | Oct 14 2026 | ASR (NeMo/Parakeet-style) | yes (25 langs; phone GGUF build = 8 langs incl. PT) | 254M; GGUF via qvac-fabric-speech.cpp; benchmarked on S26 Ultra; vendor says fastest of models tested on 6 phones | Apache-2.0 |
| **IBM granite-speech-5.0-470m-turboctc** (multilingual revision) https://huggingface.co/ibm-granite/granite-speech-5.0-470m-turboctc | Oct 21 2026 (EN-only Aug 25) | CTC ASR, native punctuation, noise/reverb robust, "smartphones/edge" | yes | 470M; transformers only — no ONNX/GGUF/sherpa build found | Apache-2.0 |
| **IBM granite-speech-5.0-2b** https://huggingface.co/ibm-granite/granite-speech-5.0-2b | Oct 20 2026 | Speech-LLM ASR + translation, keyword biasing (2000 terms) | yes | 2B; llama.cpp support claimed by IBM; ~2-min segments | Apache-2.0 |
| **IBM granite-speech-4.1-2b-plus** (weights update) https://huggingface.co/ibm-granite/granite-speech-4.1-2b-plus | updated Oct 19 2026 (orig Apr 28) | ASR + speaker-attributed ASR + word timestamps (no punctuation) | yes | 2B | Apache-2.0 |
| Cactus Whistle https://cactuscompute.com/blog/whistle | Oct 2 2026 | 16.9 MB CPU ASR | **no PT** (en/de/fr/es/it/nl/pl) | tiny | Needle JNI `.cact` | **Adopted for Whistle languages on low-end**; Whisper Tiny for pt + other majors — `docs/WHISTLE_EVAL.md` |
| Falcon-ASR (TII) https://huggingface.co/blog/tiiuae/falcon-asr | Oct 7 2026 | Arabic-focused ASR, also PT | yes | 1.6B | not checked |

Not October but relevant: **MOSS-Transcribe-Diarize 0.9B** (Jul 9 2026, Apache-2.0, 50+ langs incl. PT, end-to-end ASR+diarization+timestamps, up to 90 min single pass; won MLC-SLM 2026 incl. PT) https://github.com/OpenMOSS/MOSS-Transcribe-Diarize — no mobile build found. Leaderboard top (ARK-ASR-3B/0.6B) has **no Portuguese**.
Open ASR Leaderboard (English avg, live): 1 Edge0/ARK-ASR-3B 4.76, 2 MOSS-Transcribe-preview-2B 4.87, 3 MOSS-Transcribe-Diarize 5.17, ... Qwen3-ASR-1.7B, granite-5.0-2b ~5.68 (rank 10 per https://packetnebula.com/articles/granite-speech-5-0-2b-second-open-claim-tenth-live-board/). https://huggingface.co/datasets/hf-audio/open-asr-leaderboard

Beats Parakeet v3 / Whisper turbo **in Portuguese**? No published PT WER head-to-head found for any of these. TranscribePsy is claimed to beat models 3-4x its size on MLS (multilingual), but it was not compared with Parakeet v3 0.6B or Whisper turbo directly — UNVERIFIED for PT.
Realistic on S26 Ultra (12 GB): TranscribePsy (yes, GGUF, tested on S26U); Granite TurboCTC (likely, needs ONNX export — unverified); Granite 2B / MOSS 0.9B (possible quantized, but no Android runtime/benchmark found).
Recommendation change: keep Parakeet v3 (sherpa-onnx) default; add TranscribePsy-EU-sub as a "fast/light" A/B candidate; evaluate Granite TurboCTC (ONNX export) and MOSS-Transcribe-Diarize / Granite 4.1-2b-plus (end-to-end diarization) on real PT-BR meetings before switching. Benchmark on our own audio.

## 6. Multilingual requirement — language coverage (checked 2026-10-26)
| Model | # langs (official) | PT | ES | FR | DE | JA | ZH | Phone-realistic | License | Source |
|---|---|---|---|---|---|---|---|---|---|---|
| Whisper large-v3-turbo | 99 (quality varies a lot by language) | y | y | y | y | y | y | yes (sherpa-onnx/whisper.cpp/QNN) | MIT | https://github.com/openai/whisper |
| Parakeet TDT 0.6B v3 | 25 European | y | y | y | y | **n** | **n** | yes (sherpa-onnx int8) | CC-BY-4.0 | https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html |
| TranscribePsy-250M-EU | 25 European (laptop) | y | y | y | y | n | n | — | Apache-2.0 | https://huggingface.co/blog/qvac/transcribepsy-250m |
| TranscribePsy-250M-EU-sub (GGUF phone) | 8 (nl,en,fr,de,it,pl,pt,es) | y | y | y | y | n | n | yes (tested S26U) | Apache-2.0 | same |
| Granite Speech 5.0-470M TurboCTC | 7 (en,fr,de,es,pt,it,ja) | y | y | y | y | y | n | probably (needs ONNX export; unverified) | Apache-2.0 | https://huggingface.co/ibm-granite/granite-speech-5.0-470m-turboctc |
| Granite Speech 5.0-2B | 6 ASR (en,fr,de,es,pt,ja) | y | y | y | y | y | n (only EN→ZH translation) | heavy/unverified | Apache-2.0 | https://huggingface.co/ibm-granite/granite-speech-5.0-2b |
| Granite Speech 4.1-2B-Plus | 5 (en,fr,de,es,pt) | y | y | y | y | n | n | heavy/unverified | Apache-2.0 | https://huggingface.co/ibm-granite/granite-speech-4.1-2b-plus |
| MOSS-Transcribe-Diarize 0.9B | 50+ | y | y | y | y | y | y | no mobile build found | Apache-2.0 | https://github.com/OpenMOSS/MOSS-Transcribe-Diarize |
| SenseVoice Small | 5 (zh,yue,en,ja,ko) | n | n | n | n | y | y | yes (sherpa-onnx, Kotlin) | check FunASR model license | https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html |
| Meta Omnilingual ASR | 1600+ | y | y | y | y | y | y | small CTC variants plausible; no official Android build verified | Apache-2.0 | https://github.com/facebookresearch/omnilingual-asr/ |
| Voxtral Transcribe 3 (Mini 4B / Small) | 50 | (PT not individually verified) | y | y | ? | ? | ? | Mini 4B = too heavy/unverified on phone | Mistral terms | https://mistral.ai/news/voxtral-3/ |
| Qwen3-ASR | 30 langs + 22 zh dialects (per search; list not opened) | ? | ? | ? | ? | ? | y | 0.6B possible, unverified | Apache-2.0 (unverified) | — |
| Gemma 4 E2B/E4B audio | pretrained 140+, out-of-box 35+ | likely | | | | | | yes (LiteRT-LM) | Apache-2.0 | https://ai.google.dev/gemma/docs/core/model_card_4 |

LLMs: Gemma 4 E2B/E4B — 140+ pretrained, 35+ out-of-the-box, 128K ctx. Qwen3 family (incl. 4B-Instruct-2507) — 119 languages/dialects (https://github.com/QwenLM/Qwen3/blob/main/docs/source/getting_started/concepts.md), 262K ctx.
Diarization: sherpa-onnx pipeline = pyannote segmentation (acoustic VAD/speaker-change) + speaker embeddings + clustering; no language parameter in the API, so it is language-independent by design. NOT verified with a published multilingual DER; the 3D-Speaker ERes2Net model is trained on Chinese data (zh-cn in name) — prefer a multilingual-trained embedding (WeSpeaker VoxCeleb / NeMo TitaNet) and test.
Language ID / code-switching: Whisper has built-in language detection (per 30 s window; code-switching within a window is weak). Parakeet v3 auto-detects among its 25 languages per the NVIDIA card (not re-opened here). SenseVoice supports `auto`. Granite 5.0-2B uses language-ID *prompting* (you name it). No model verified for robust intra-sentence code-switching.
Multilingual WER evidence: Yap FLEURS on-device benchmark (25 langs, Whisper turbo vs Parakeet v3) — e.g. es 2.53/2.79, it 2.73/2.52, pt 3.28/3.62, en 3.92/4.48, de 5.08/4.84, fr 6.62/5.29, pl 7.7/8.21, el 11.96/31.66 (https://yapapp.cc/data/on-device-asr-benchmark). No verified FLEURS JA/ZH numbers for Whisper turbo in this research.

### Revised multilingual stack
- Language router: detect language (Whisper LID or user setting) per recording.
- European languages (25): Parakeet v3 (default, fast). A/B: TranscribePsy-EU-sub for the 8 MLS languages.
- Everything else (JA, ZH, KO, AR, HI, …) and mixed-language: Whisper large-v3-turbo (sherpa-onnx, later QNN NPU). ZH/JA/KO light option: SenseVoice Small.
- Watch list: Omnilingual ASR (long tail), MOSS-Transcribe-Diarize (end-to-end diarization 50+ langs) when mobile builds exist.
- Diarization: sherpa-onnx pyannote-3.0 + multilingual embedding (test TitaNet/WeSpeaker vs 3D-Speaker).
- LLM: Gemma 4 E4B (default) / E2B (light); Qwen3-4B-2507 as fallback for languages outside Gemma's 35 tuned ones. Prompt: "answer in the transcript's language" or user-chosen language.

## 7. Embedding choice (catalog default)

**Preferred embedding id:** `3dspeaker-eres2net-base`
(`3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` from sherpa-onnx `speaker-recongition-models`).

**Why not WeSpeaker / TitaNet as default:** WeSpeaker CAM++ and NeMo TitaNet-small in the sherpa release are **VoxCeleb / English-centric**. 3D-Speaker ERes2Net is trained on the 3D-Speaker corpus (broader speaker variety beyond VoxCeleb EN). Language-independent clustering still benefits from a less EN-narrow embedding for PT-BR meetings. WeSpeaker and TitaNet remain catalog alternates; `LocalDiarizer` picks the first Ready id in order: 3dspeaker → WeSpeaker → TitaNet.

## 8. Verified catalog SHA-256 (2026-10-26)

Computed locally from official downloads (k2-fsa GitHub release assets) or Hugging Face LFS oid (identical to content SHA-256):

| Catalog id | Source | SHA-256 |
|---|---|---|
| `parakeet-tdt-0.6b-v3-int8` | k2-fsa `asr-models` / `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2` (487170055 B) | `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf` |
| `whisper-large-v3-turbo` | k2-fsa `asr-models` / `sherpa-onnx-whisper-turbo.tar.bz2` (563790207 B) | `b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22` |
| `sensevoice-small` | k2-fsa `asr-models` / `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` (163002883 B) | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` |
| `pyannote-segmentation-3.0` | k2-fsa `speaker-segmentation-models` / `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488` |
| `3dspeaker-eres2net-base` | k2-fsa `speaker-recongition-models` ONNX | `1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b` |
| `wespeaker-voxceleb` | same release / `wespeaker_en_voxceleb_CAM++.onnx` | `c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef` |
| `titanet-small` | same release / `nemo_en_titanet_small.onnx` | `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e` |
| `streaming-zipformer-en-20m-mobile` | k2-fsa `asr-models` mobile pack | `753a5362538539212442efbfb8dd5748db82e35e7deaec5330f49c56e81e40fd` |
| `streaming-zipformer-es-kroko` | k2-fsa `asr-models` | `31b2230a95d23290b308b393da930015a4b2105cb3abb9367aed35f7fcf29cf1` |
| `streaming-zipformer-multilang-2025-02` | k2-fsa `asr-models` ar/en/id/ja/ru/th/vi/zh | `28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4` |
| `gemma-4-e4b` | HF `litert-community/gemma-4-E4B-it-litert-lm` LFS oid | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| `gemma-4-e2b` | HF `litert-community/gemma-4-E2B-it-litert-lm` LFS oid | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `qwen3-4b-instruct-2507` | HF `unsloth/Qwen3-4B-Instruct-2507-GGUF` Q4_K_M LFS oid | `3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597` |

`LocalModelManager` verifies `sha256` after download and rejects mismatches. Gemma/Qwen downloads still need the user to accept HF gated licenses where applicable.
