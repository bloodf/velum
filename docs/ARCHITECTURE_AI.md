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

API diarization throws until a compatible remote contract is chosen; Local remains the default.

### Summaries / templates / agents (local)

| Role | Model | Runtime |
|---|---|---|
| Default | **Gemma 4 E4B** | LiteRT-LM |
| Light | **Gemma 4 E2B** | LiteRT-LM |
| Fallback | **Qwen3-4B-Instruct-2507** | llama.cpp (GGUF) |

- **Chunked map-reduce** when context or RAM is tight (typical 12 GB phones).
- Output language: recording language **or** a user-chosen language.

### Delivery & jobs

- Models are **downloaded on demand** (not in the APK, not in git) via `LocalModelManager` (Ktor → `models/`).
- Catalog entries without a known release URL stay **BlockedOnUrl** (never fake Ready).
- Processing runs through `RecordingProcessor` (decode → ASR → diarize → markdown → summarize → DB). WorkManager + thermal monitoring still to land.

## 3. Code shape

```
Transcriber  ── LocalTranscriber (sherpa-onnx OfflineRecognizer on Android)
             └─ OpenAiTranscriber (MissingApiKeyException when key absent)

Diarizer     ── LocalDiarizer (OfflineSpeakerDiarization + VoiceProfileStore cosine match)
             └─ OpenAiDiarizer (UnsupportedOperationException — Local required)

LlmEngine    ── LocalLlmEngine (LiteRT-LM on Android when Ready; JVM unsupported)
             └─ OpenAiLlmEngine

OpusDecoder  ── Ogg demux (common) + kopus (Android/JVM); iOS unsupported for now
```

- `LocalModelCatalog` / `LocalModelManager` — download, extract archives, delete, Ready only when expected files exist.
- `VoiceProfileStore` — embeddings + display names under `models/voices/`.
- `RecordingProcessor` — pipeline + map-reduce summarization for long transcripts.
- Settings UI: per-task Local/API toggles + **Model manager** screen.
- Weights stay under device storage / `models/` (gitignored); never committed.
- UI: `MarkdownPreview` for transcripts and notes.

## 4. Non-goals (remaining)

- Bundling or committing model weights
- Streaming ASR UI
- iOS on-device ASR / Opus decode / LLM runtimes (Android-first; same interfaces later)
- WorkManager thermal checkpoints
