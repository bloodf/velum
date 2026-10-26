# Velum AI architecture

Product decisions for transcription, diarization, and summaries/templates/agents.
Research backing: [LOCAL_MODELS_RESEARCH.md](LOCAL_MODELS_RESEARCH.md).

## 1. Per-task Local vs API (mixing allowed)

For **each** of these tasks the user chooses in Settings:

| Task | Setting key | Default |
|---|---|---|
| Transcription | `transcriptionMode` | Local |
| Speaker diarization | `diarizationMode` | Local |
| Summaries / templates / agents | `summaryMode` | Local |

- **Local** — on-device engines (downloaded models, never shipped in the APK).
- **API** — the user’s own OpenAI-compatible endpoint (base URL + API key + model id), stored in the platform secure store.

Mixing is first-class: e.g. Local transcription + Local diarization + API summaries, or any other combination. The router (`AiTaskRouter`) resolves the active `Transcriber` / `Diarizer` / `LlmEngine` from these settings at call time.

## 2. Multilingual product (many languages)

The app is multilingual — not EN/PT only. Language comes from **auto-detect** or a **user setting** (`audioLanguage` / `outputLanguage`).

### Transcription (local router)

| Path | When | Runtime |
|---|---|---|
| **Parakeet TDT 0.6B v3** | Its 25 European languages | sherpa-onnx int8 |
| **Whisper large-v3-turbo** | Everything else + mixed / code-switched audio | sherpa-onnx (later QNN NPU) |
| **SenseVoice Small** | Light CJK option (zh / yue / ja / ko) | sherpa-onnx |

API transcription uses the user’s chosen OpenAI-compatible `/audio/transcriptions` model.

### Diarization (local)

- Segmentation: **sherpa-onnx** + **pyannote-3.0**
- Embeddings: evaluate **WeSpeaker** / **TitaNet** vs **3D-Speaker**; prefer a multilingual-trained embedding
- **Named voice profiles** — store embeddings per person; cosine-match clusters to names

API diarization is a stub until a compatible remote contract is chosen; Local remains the default.

### Summaries / templates / agents (local)

| Role | Model | Runtime |
|---|---|---|
| Default | **Gemma 4 E4B** | LiteRT-LM |
| Light | **Gemma 4 E2B** | LiteRT-LM |
| Fallback | **Qwen3-4B-Instruct-2507** | llama.cpp (GGUF) |

- **Chunked map-reduce** when context or RAM is tight (typical 12 GB phones).
- Output language: recording language **or** a user-chosen language.

### Delivery & jobs

- Models are **downloaded on demand** (not in the APK, not in git).
- Processing runs in a **resumable WorkManager** job with **thermal monitoring** (Android `PowerManager` thermal status) and checkpoint resume.

## 3. Code shape

```
Transcriber  ── LocalTranscriber (stub → sherpa-onnx)
             └─ OpenAiTranscriber

Diarizer     ── LocalDiarizer (stub → sherpa-onnx + profiles)
             └─ OpenAiDiarizer (stub)

LlmEngine    ── LocalLlmEngine (stub → LiteRT-LM / llama.cpp)
             └─ OpenAiLlmEngine
```

- `LocalModelCatalog` / `LocalModelManager` — download, delete, status for on-device weights.
- Settings UI: per-task Local/API toggles + **Model manager** screen (Plaud-style list / black CTAs).
- Weights stay under device storage / `models/` (gitignored); never committed.

## 4. Non-goals (this slice)

- Bundling or committing model weights
- Full sherpa-onnx / LiteRT-LM JNI wiring (interfaces + stubs only)
- Streaming ASR UI
- iOS on-device runtimes (Android-first; same interfaces later)
