# Velum AI architecture

Product decisions for transcription, diarization, and summaries/templates/agents.
Research backing: [LOCAL_MODELS_RESEARCH.md](LOCAL_MODELS_RESEARCH.md).
Plaud cloud inventory → local replacements: [APK_ANALYSIS.md](APK_ANALYSIS.md) §3.

## 0. No Plaud cloud

Velum never contacts `api.plaud.ai` (or regional / realtime / marketing hosts). There is **no** Plaud auth, billing, membership, telemetry (Datadog / PostHog / Crashlytics), hosted share links, teams, or SoundPlus license path.

| Plaud group | Velum replacement |
|---|---|
| `/auth/*`, `/user-app/*` | Removed. Optional local PIN / biometrics only |
| `/user/*`, `/config/*`, feature-gating | Local DataStore / SQLDelight; features on |
| `/file/*`, tags, S3 | Local app storage + Room / SQLDelight |
| `/ai/transcript*`, polish | Local ASR (whisper.cpp / sherpa-onnx) + optional LLM polish |
| Diarization / speakers | Local embeddings + `VoiceProfileStore` |
| `/ai/sum*`, `/summary/*`, templates | On-device LLM or **BYO** OpenAI-compatible key |
| `/ask/*`, memory, search | Local chat + FTS / memory table |
| `/share/*`, `/team-app/*`, marketing, Stripe / Shopify | Removed; Android share sheet for export |
| Datadog / PostHog / FCM | Removed; local logs / notifications only |

### Blocked on owner (cannot synthesize locally)

- **`snSignature`** (`/device/sn-sign`) — one-time import into Android Keystore; required as FE10 on every connect for protocol ≥ 20
- **Bind token** (`/device/bind`, bind-token, apply-token) — one-time import for cmd 1
- **User RSA E2EE keypair** (`/e2ee/*`) — one-time import if the device still requests FE11/FE12; prefer turning E2EE off in the official app before migrate (verify on hardware)

Firmware catalogue / automatic OTA, Find My registration, and SoundPlus remain out of scope.

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
| **Whistle** | Low-end / Tiny + language ∈ en/de/fr/es/it/nl/pl | Needle JNI (`.cact`) |
| **Whisper Tiny** | Low-end / Tiny + pt and other important langs | sherpa-onnx int8 |
| **Parakeet TDT 0.6B v3** | Mid/standard — its 25 European languages | sherpa-onnx int8 |
| **Whisper large-v3-turbo** | Everything else + mixed / code-switched audio | sherpa-onnx (later QNN NPU) |
| **SenseVoice Small** | Light CJK option (zh / yue / ja / ko) | sherpa-onnx |

Important language minimum (pt, en, es, fr, de, it, nl, pl, ja, zh, ko, ru, ar, hi, tr): `ImportantLanguages` / `docs/WHISTLE_EVAL.md`.

API transcription uses the user’s chosen OpenAI-compatible `/audio/transcriptions` model.

### Diarization (local)

- Segmentation: **sherpa-onnx** + **pyannote-3.0**
- Embeddings: prefer **`3dspeaker-eres2net-base`** (3D-Speaker multilingual training) over VoxCeleb-only WeSpeaker; TitaNet / WeSpeaker stay as catalog alternates
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
- Processing runs through `RecordingProcessor` (decode → ASR → diarize → markdown → summarize → DB) on Android via **WorkManager** (`RecordingProcessWorker`) with **thermal pause/resume** (`ThermalMonitor` + exponential backoff) and DB checkpoints (`paused_thermal`).
- Product default for every task is **Local** when the required model is Ready; otherwise the UI prompts **Download** or switch to **API** (`resolveModeOrPrompt`). Per-task override remains in Settings.

## 3. Code shape

```
Transcriber  ── LocalTranscriber (sherpa-onnx OfflineRecognizer on Android)
             └─ OpenAiTranscriber (MissingApiKeyException when key absent)

Streaming    ── OnlineRecognizer when streaming pack detected; else overlapping Offline windows
             └─ FakeStreamingTranscriber on JVM (tests / live demo without models)

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
- iOS on-device ASR / Opus decode / LLM runtimes (Android-first; same interfaces later)
- Any Plaud-hosted auth, billing, telemetry, or OTA catalogue
