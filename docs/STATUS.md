# Velum status

Owner standing rule: nothing may remain stub/demo-only. Prefer **blocked-on-owner** over fake success.

## Working

### Protocol / BLE
- [x] Protocol-20 framing, unlock (FE10/FE11/FE12), handshake, list (26), download (28/type-2/29), battery (9)
- [x] Forbidden opcodes never sent: **5, 104, 128, 0xFE20, 143 (OTA)**; never write settings **4 / 26**
- [x] Unused cmds left alone: 5, 13, 38, 50, 51, 109, 141, 142, 145, 151
- [x] Fixed session key/nonce/aad; plaintext = `seq ‖ packet`; FE10 snSignature on every connect (prot ≥ 20)
- [x] Cmd 138 feature bit 0x08 (AES-GCM Wi-Fi); SoftAP + length-prefixed TCP `:8081` with SoftAP per-socket bind, token preamble, BLE fallback (JVM fake E2E green; confirm on hardware)
- [x] Android: `allowBackup=false`, cleartext off, networkSecurityConfig + dataExtractionRules; release R8 on
- [x] Model downloads: HTTPS + SHA-256 required (streaming hash + atomic rename); ASR catalog pins included
- [x] BYO API base URL validated (`AiBaseUrl`: HTTPS except localhost; block link-local metadata)
- [x] `VelumApplication` process-scoped `AppContainer` / `AiRuntimeHolder` for WorkManager cold start
- [x] Project **881/883** Note Pro family (no OTA)
- [x] Per-model command tables (`DeviceModel` / `CommandTable`)
- [x] `PlaudDeviceClient` + JCA `SessionCipher` / `FileDecryptor` (Android + JVM)
- [x] `FakeBleTransport` / `FakePlaudPeripheral` + JVM E2E unlock→download→decrypt (rejects empty FE10)
- [x] Device screen: scan / connect / sessions / battery when identity keys imported
- [x] Static analysis notes: `docs/APK_ANALYSIS.md`

### Audio / AI
- [x] Ogg demux + Opus decode (kopus) → PCM
- [x] OpenAI-compatible API: URL / key / model list refresh / transcribe / chat (BYO URL only; no Plaud hosts)
- [x] Per-task Local vs API settings (mix allowed); **default Local** for every task
- [x] `resolveModeOrPrompt`: Local when model Ready, else UI prompts Download or switch to API
- [x] `RecordingProcessor`: decode → ASR → diarize → Markdown transcript → summarize → DB
- [x] Glossary + memory injected into ASR `initial_prompt` / LLM system prompts
- [x] Map-reduce summarization for long transcripts
- [x] LocalModelManager: real Ktor download + archive extract + disk Ready checks + **SHA-256 verify**
- [x] ASR catalog URLs (Parakeet / Whisper turbo / SenseVoice from k2-fsa)
- [x] Streaming ASR catalog packs (Zipformer EN mobile / ES Kroko / multilingual) with verified SHA-256
- [x] Diarization + embedding + LLM catalog URLs + verified SHA-256 (pyannote, 3D-Speaker preferred, WeSpeaker, TitaNet, Gemma E4B/E2B LiteRT-LM, Qwen3-4B GGUF) — see `docs/LOCAL_MODELS_RESEARCH.md` §8
- [x] Android sherpa-onnx `OfflineRecognizer` (Parakeet / Whisper / SenseVoice)
- [x] `StreamingTranscriber` (Android OnlineRecognizer when streaming pack Ready; else overlapping Offline windows; JVM `FakeStreamingTranscriber`)
- [x] Phone mic: runtime RECORD_AUDIO + rationale → PCM WAV (`MicCapture` / `WavWriter`) → process via WorkManager
- [x] Mic foreground service (`MicRecordingService`, `FOREGROUND_SERVICE_MICROPHONE`) + Pause/Stop notification; process-death WAV recover (`WavWriter.recoverOrFinalize`); POST_NOTIFICATIONS on API 33+
- [x] Processing FGS via WorkManager `setForegroundAsync` (`FOREGROUND_SERVICE_DATA_SYNC`) on `RecordingProcessWorker`
- [x] Live record UI with partial Markdown (mic when permitted; else PCM demo stream)
- [x] Mind-map from summary H2 + list items ("Mapa mental" sheet)
- [x] Local diarization + cosine voice profiles (`VoiceProfileStore`); preferred embedding `3dspeaker-eres2net-base`
- [x] Local LLM LiteRT-LM on Android when Gemma Ready (default catalog id `gemma-4-e4b`)
- [x] Ask AI on recording notes via local/`AiTaskRouter` LLM (no Plaud ask endpoints)
- [x] Compose Markdown preview for Fontes / Notas (deterministic Compose renderer: bold speakers, timestamps, headings/lists, task checkboxes; Roborazzi fixtures seed diarized transcript + summary)
- [x] Summary/template H2 headings follow output language (pt-BR / en pinned; other langs via LLM instruction) — `SummaryLocalization`
- [x] Dark theme + TalkBack labels + 48 dp chrome targets + secondary ink contrast; player disabled without local audio
- [x] Generate sheet + retranscribe launch processor
- [x] AutoFlow matcher + runner on matching recordings
- [x] AutoFlow post-actions: share Markdown intent / calendar ACTION_INSERT
- [x] WorkManager (`RecordingProcessWorker`) + `ThermalMonitor` pause/resume + DB checkpoints (`paused_thermal`)
- [x] First-run UX guiding model downloads (or Use API / Skip) → Model manager / Account
- [x] CI E2E: Fake BLE → mock API ASR/LLM → Markdown in DB (`DeviceToSummaryE2ETest`)

### Local data / search / templates
- [x] SQLite FTS5 over recording title+transcript+summary and templates (raw `SqlDriver` DDL/MATCH — SQLDelight dialect SOEs on FTS5); Files/Templates search uses FTS with `ListSearch` fallback
- [x] Glossary + memory tables in `Velum.sq`; CRUD under Personalization / Memory
- [x] Local JSON/Markdown templates create/edit persist locally (community/cloud templates removed)
- [x] Built-in catalog: **15 Velum-original templates** (`velum.*`, one per Plaud category key) with pt-BR/en names, recording-language Markdown output, omit-empty sections — `BuiltinTemplateCatalog` / `docs/velum_templates.json`
- [x] CalendarProvider read current/next event title to name recordings after sync (permission-denied reported clearly)
- [x] Runtime READ_CALENDAR permission with rationale (Integrations + AutoFlow blocked path)

### App surfaces
- [x] Search (Files / Templates)
- [x] Share / export Markdown (Android share sheet; desktop clipboard/file)
- [x] Trash + move-to-folder
- [x] FAB start recording (mic WAV) + import audio → processor
- [x] Find/replace on transcript
- [x] Preferences / personalization / languages persist
- [x] Key import UI (Settings → paste fields and/or Plaud JSON v1 → Keystore; optional templates as `plaud-import-*`) — see `docs/PLAUD_IMPORT.md`
- [x] Integrations: local device calendar (no Plaud/Google OAuth server path required)
- [x] Roborazzi phone screenshots (~1080×2400) in CI artifacts
- [x] Instrumented Compose UI smoke tests (`MainFlowUiTest`)
- [x] Signed debug APK (Android debug keystore) as CI artifact
- [x] UI comparison vs owner Plaud reference zip → `docs/UI_COMPARISON.md`

## Reviews

- [x] Full security / systems / UX review → `docs/REVIEW_2026-10.md` (2026-10-26)
- [x] Critical/high/medium findings fixed on `main` (`eb2b2b6`, `aa2af18`, `c61e5ae`) except intentional deferrals listed in that doc
- [x] Forbidden opcodes **5 / 104 / 128 / 0xFE20 / 143** + settings **4 / 26** re-verified with client send-path tests

## In progress

- [x] Chunked / windowed ASR + BLE-to-file sync (Y14/Y17; 200 MiB hard fail remains)
- [x] Mic + processing foreground services (FOREGROUND_SERVICE) — mic `microphone` + processing `dataSync`
- [x] SoftAP per-socket bind + token preamble + BLE fallback (fixed AEAD nonce kept for Plaud SoftAP parity — see `docs/protocol/WIFI_FAST_PATH.md`)

## Blocked on owner

- [ ] **One-time device identity values** (`snSignature` / `sn-sign` + bind token + optional RSA) from owner Plaud dump — **cannot be generated locally**. Import UI + JSON schema ready (`docs/PLAUD_IMPORT.md`); waiting on Heitor export.
- [ ] Confirm Ogg Opus rate/channels + PLAUD.AI decrypt against a real Note Pro capture

## Removed (server inventory → local)

- Auth / billing / membership / Stripe / Shopify / telemetry (Datadog, PostHog, Crashlytics, Firebase Messaging)
- Plaud host URLs (`api.plaud.ai`, regional, `wss://realtime.plaud.ai`, …) — not present in app code; BYO OpenAI-compatible base URL only
- Community/cloud templates, hosted share links, teams, OTA catalogue, SoundPlus license

## Confirm only on owner’s real Plaud Note Pro

- Live BLE advertise `portVersion` / project 881, unlock with owner keys, battery notify
- Full multi-hour download resume + CRC
- On-device Parakeet/Whisper/Gemma quality + thermal throttling on physical silicon
- Whether FE10 alone works with E2EE off (no FE11) after one-time key import
- Wi-Fi SoftAP against real Note Pro hardware (JVM fake path is green)
