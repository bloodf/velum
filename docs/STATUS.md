# Velum status

Owner standing rule: nothing may remain stub/demo-only. Prefer **blocked-on-owner** over fake success.

**No unimplemented UI (app-wide):** remove any menu item, button, tab, row, sheet entry, or toggle whose feature is not actually implemented and working. No “coming soon”, disabled placeholders, or dead links — covers library top bar, FAB overlay, Explore, Integrations, AutoFlow, Device, Templates, recording detail menus, share sheet, onboarding, desktop layout, and Settings. Velum has **no telemetry / analytics / crash reporting**; do not expose opt-in toggles or strings that imply otherwise.

## Working

### Protocol / BLE
- [x] Pluggable `RecorderDriver` registry (`:recorder`): Plaud + Omi/Friend + Pocket; Limitless codec vendored; see `docs/DEVICES_ROADMAP.md` / `THIRD_PARTY.md` — **Omi/Pocket/Limitless not verified on hardware**
- [x] Protocol-20 framing, unlock (FE10/FE11/FE12), handshake, list (26), download (28/type-2/29), battery (9)
- [x] Forbidden opcodes never sent: **5, 104, 128, 0xFE20, 143 (OTA)**; never write settings **4 / 26** (every model)
- [x] Unused cmds left alone: 5, 13, 38, 50, 51, 109, 141, 142, 145, 151
- [x] Fixed session key/nonce/aad; plaintext = `seq ‖ packet`; FE10 snSignature on every connect (prot ≥ 20)
- [x] Cmd 138 feature bit 0x08 (AES-GCM Wi-Fi); SoftAP + **WebSocket `:8081`** (`org.java_websocket`, Plaud parity) with SoftAP per-socket bind, token preamble, fixed session nonce, BLE fallback (JVM fake E2E green; confirm on hardware)
- [x] Android: `allowBackup=false`, cleartext off, networkSecurityConfig + dataExtractionRules; release R8 on
- [x] Model downloads: HTTPS + SHA-256 required (streaming hash + atomic rename); ASR catalog pins included
- [x] BYO API base URL validated (`AiBaseUrl`: HTTPS except localhost; block link-local metadata)
- [x] `VelumApplication` process-scoped `AppContainer` / `AiRuntimeHolder` for WorkManager cold start
- [x] Project **881/883** Note Pro family (no OTA)
- [x] Per-model command tables (`DeviceModel` / `CommandTable`)
- [x] **Device capability registry** — auto-detect on scan/connect (product code + name prefix); gate Wi‑Fi SoftAP; show model name on device screen
- [x] `PlaudDeviceClient` + JCA `SessionCipher` / `FileDecryptor` (Android + JVM)
- [x] `FakeBleTransport` / `FakePlaudPeripheral` + JVM E2E unlock→download→decrypt (rejects empty FE10)
- [x] Device screen: scan / connect / sessions / battery when identity keys imported
- [x] Static analysis notes: `docs/APK_ANALYSIS.md`

### Supported devices matrix

| Model | Product code(s) | BLE protocol | Wi‑Fi SoftAP `:8081` | Audio formats | Notes |
|---|---|---|---|---|---|
| Plaud One | 860 | 30–39 | Yes (if handshake) | Ogg Opus in PLAUD.AI | ECC unlock |
| NotePin | 880 | 20–29 | Yes (if handshake) | Ogg Opus / ADPCM | |
| NotePin S | 882 | 20–29 | Yes (if handshake) | Ogg Opus / ADPCM | |
| Note Pro | 881, 883 | 20–29 | Yes (if handshake) | Ogg Opus in PLAUD.AI | Never send 143 OTA |
| Note | 888 | 10–29 | **No** | Raw Opus / Ogg Opus | BLE transfer only |
| Unknown / newer same protocol | other | 20–39 (default) | Attempt after handshake | Ogg Opus (conservative) | Refined by handshake |

Phone-only / external (no Plaud required):

| Source | Path | Pipeline |
|---|---|---|
| Phone mic (+ BT headset / USB mic) | Mic FGS, pause/resume, markers, input-source picker | Same ASR → diarize → summary |
| SAF / share sheet (“Open with Velum”) | mp3, m4a/aac, wav, ogg/opus, flac, amr, 3gp, webm, mp4 | MediaCodec normalize → PCM WAV 16 kHz → same pipeline; SHA-256 dedupe |
| USB OTG / SD / sync folder | SAF tree watched folders + re-sync | Same import pipeline |
| User template JSON | Templates → JSON import | Upserts local templates; built-ins untouched |

### Audio / AI
- [x] Ogg demux + Opus decode (kopus) → PCM
- [x] OpenAI-compatible API: URL / key / model list refresh / transcribe / chat (BYO URL only; no Plaud hosts)
- [x] Per-task Local vs API settings (mix allowed); **default Local** for every task
- [x] `resolveModeOrPrompt`: Local when model Ready, else UI prompts Download or switch to API
- [x] `RecordingProcessor`: decode → ASR → diarize → Markdown transcript → summarize → DB
- [x] Glossary + memory injected into ASR `initial_prompt` / LLM system prompts
- [x] Map-reduce summarization for long transcripts
- [x] LocalModelManager: real Ktor download + archive extract + disk Ready checks + **SHA-256 verify**
- [x] ASR catalog URLs (Parakeet / Whisper turbo / Whisper Tiny / SenseVoice from k2-fsa)
- [x] Tiny / low-end ASR: **Whistle** (Needle JNI, 16.9 MB, Apache-2.0) for en/de/fr/es/it/nl/pl on low RAM / Tiny tier; **Whisper Tiny** for pt and other important languages; SHA-verified `.cact` download; manual Auto/Tiny/Standard/Quality override → `docs/WHISTLE_EVAL.md`
- [x] Android 15+ **16 KB page size**: `libvelum_needle` linked with `-Wl,-z,max-page-size=16384` (NDK r28+); sherpa-onnx/onnxruntime, LiteRT-LM, kopus, androidx.graphics-path 16 KB-aligned; `useLegacyPackaging=false`; CI `zipalign -c -P 16` + `llvm-readelf` PT_LOAD ≥ 0x4000 on 64-bit libs
- [x] Dark-theme primary buttons: `VelumOnBlack` / Material `onPrimary` (no white-on-white); WCAG AA contrast test
- [x] Model catalog + onboarding copy is user-facing (pt-BR + en); no engine/JNI/doc jargon in UI; recommended-model pack on first run
- [x] Voice profiles: encrypted embeddings, multi-sample centroid, auto-label + confidence, refine on confirm, CRUD/merge/export/import, management screen
- [x] Streaming ASR catalog packs (Zipformer EN mobile / ES Kroko / multilingual) with verified SHA-256
- [x] Diarization + embedding + LLM catalog URLs + verified SHA-256 (pyannote, 3D-Speaker preferred, WeSpeaker, TitaNet, Gemma E4B/E2B LiteRT-LM, Qwen3-4B GGUF) — see `docs/LOCAL_MODELS_RESEARCH.md` §8
- [x] Android sherpa-onnx `OfflineRecognizer` (Parakeet / Whisper / SenseVoice)
- [x] `StreamingTranscriber` (Android OnlineRecognizer when streaming pack Ready; else overlapping Offline windows; JVM `FakeStreamingTranscriber`)
- [x] Phone mic: runtime RECORD_AUDIO + rationale → PCM WAV (`MicCapture` / `WavWriter`) → process via WorkManager
- [x] Mic foreground service (`MicRecordingService`, `FOREGROUND_SERVICE_MICROPHONE`) + Pause/Stop notification; process-death WAV recover (`WavWriter.recoverOrFinalize`); POST_NOTIFICATIONS on API 33+
- [x] Mic first-class: pause/resume, markers, input-source picker (Mic / VoiceCommunication / Unprocessed / Camcorder for BT/USB routes)
- [x] External import hub + share intents + watched SAF folders + MediaCodec normalize + hash dedupe
- [x] Phone-first onboarding: Use phone mic / Import from a recorder / Connect a Plaud
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
- [x] AutoFlow post-actions: share Markdown intent / calendar ACTION_INSERT / folder export / webhook (gated on configuration)
- [x] WorkManager (`RecordingProcessWorker`) + `ThermalMonitor` pause/resume + DB checkpoints (`paused_thermal`)
- [x] First-run UX guiding model downloads (or Use API / Skip) → Model manager / Account
- [x] CI E2E: Fake BLE → mock API ASR/LLM → Markdown in DB (`DeviceToSummaryE2ETest`)

### Local data / search / templates
- [x] SQLite FTS5 over recording title+transcript+summary and templates (raw `SqlDriver` DDL/MATCH — SQLDelight dialect SOEs on FTS5); Files/Templates search uses FTS with `ListSearch` fallback
- [x] Glossary + memory tables in `Velum.sq`; CRUD under Personalization / Memory
- [x] Local JSON/Markdown templates create/edit persist locally (community/cloud templates removed)
- [x] Built-in catalog: **15 Velum originals** + curated **1,500 Plaud** templates (100×15 categories; unique names within category; lazy classpath asset) + optional Velum PR contributions (`contributor` credit on detail only). Plaud author/official stay in JSONL + `docs/TEMPLATES_CREDITS.md` (not shown in app UI). Discover: category tabs, FTS over name/description/body, popularity sort. Local JSON importer kept (`TemplateJsonImporter`). Sync via `scripts/sync-plaud-templates.sh`
- [x] CalendarProvider read current/next event title to name recordings after sync (permission-denied reported clearly)
- [x] Runtime READ_CALENDAR permission with rationale (Integrations + AutoFlow blocked path)

### Integrations (no owner OAuth registration)
- [x] Framework: `IntegrationRegistry` + Keystore `IntegrationAuthStore` / `WebhookSettingsStore`; OAuth providers omitted until client ID registered
- [x] Obsidian / local folder: SAF picker + persistable URI; Markdown + YAML frontmatter (`MarkdownNoteExport`); AutoFlow `folder_export`
- [x] Webhook: HTTPS URL + optional HMAC-SHA256 (`X-Velum-Signature`); schema v1 + retries; docs in `docs/WEBHOOK.md`; AutoFlow `webhook`
- [ ] Google / Notion / Slack / Microsoft OAuth — blocked on owner client registration (#49/#50)

### App surfaces
- [x] Search (Files / Templates)
- [x] Share / export Markdown (Android share sheet; desktop clipboard/file)
- [x] Trash + move-to-folder
- [x] FAB start recording (mic WAV) + import audio → processor
- [x] Find/replace on transcript
- [x] Preferences / personalization / languages persist
- [x] Key import UI (Settings → paste fields and/or Plaud JSON v1 → Keystore; optional templates as `plaud-import-*`) — see `docs/PLAUD_IMPORT.md`
- [x] Integrations screen: device calendar + folder export + webhook (working paths only; no placeholder OAuth rows)
- [x] Roborazzi phone screenshots (~1080×2400) in CI artifacts
- [x] Instrumented Compose UI smoke tests (`MainFlowUiTest`)
- [x] Signed debug APK as CI artifact — fixed `composeApp/debug.keystore` (android/androiddebugkey) so every CI debug build shares one signature (`adb install -r` without wipe)
- [x] UI comparison vs owner Plaud reference zip → `docs/UI_COMPARISON.md`
- [x] Hardware validation checklist for Note Pro owner runs → `docs/HARDWARE_VALIDATION.md`

## Reviews

- [x] Full security / systems / UX review → `docs/REVIEW_2026-10.md` (2026-10-26)
- [x] Critical/high/medium findings fixed on `main` (`eb2b2b6`, `aa2af18`, `c61e5ae`) except intentional deferrals listed in that doc
- [x] Forbidden opcodes **5 / 104 / 128 / 0xFE20 / 143** + settings **4 / 26** re-verified with client send-path tests

## In progress

- [x] Chunked / windowed ASR + BLE-to-file sync (Y14/Y17); PCM above ~48 MiB spills to temp file and is window-read (no 200 MiB hard fail)
- [x] Mic + processing foreground services (FOREGROUND_SERVICE) — mic `microphone` + processing `dataSync`
- [x] SoftAP + WebSocket `:8081` per-socket bind + token preamble + BLE fallback (fixed AEAD nonce kept for Plaud SoftAP parity — see `docs/protocol/WIFI_FAST_PATH.md`)

### Release / distribution (code-ready; Play upload needs owner secrets)

- [x] Release signing reads `VELUM_UPLOAD_*` env / CI secrets; debug keystore never used for release
- [x] Tag workflow `.github/workflows/release.yml` builds signed AAB when secrets present
- [x] R8/ProGuard minify + shrink on release; crash-free process start (Keystore/`VoiceProfileStore` guarded)
- [x] Privacy policy `docs/PRIVACY.md` + About link (pt-BR + en); Play/F-Droid metadata under `fastlane/metadata/` + `metadata/dev.velum.app.yml`
- [x] OSV CI job (`osv-scan`); SoftAP fixed AEAD nonce **accepted** (S13) — see `docs/REVIEW_2026-10.md`
- [ ] Owner: create upload keystore, set GitHub secrets, push `v*` tag, complete Play Console data-safety + internal track
- [ ] Owner: decide Renovate PR #2; open F-Droid `fdroiddata` PR from `metadata/dev.velum.app.yml`

## Blocked on owner

- [ ] **One-time device identity values** (`snSignature` / `sn-sign` + bind token + optional RSA) from owner Plaud dump — **cannot be generated locally**. Import UI + JSON schema + unit tests ready (`docs/PLAUD_IMPORT.md`); waiting on Heitor export. Hardware checklist: `docs/HARDWARE_VALIDATION.md`.
- [ ] Confirm Ogg Opus rate/channels + PLAUD.AI decrypt against a real Note Pro capture

## Removed (server inventory → local)

- Auth / billing / membership / Stripe / Shopify / telemetry (Datadog, PostHog, Crashlytics, Firebase Messaging)
- Plaud host URLs (`api.plaud.ai`, regional, `wss://realtime.plaud.ai`, …) — not present in app code; BYO OpenAI-compatible base URL only
- Community/cloud templates, hosted share links, teams, OTA catalogue, SoundPlus license
- Unimplemented chrome audit: Preferences “Help improve Velum” / usage-data toggle + `prefs.help_improve`; Preferences App lock (info-only dialog); Settings hub Current workspace (noop); Integrations “More apps coming” panel; Templates Photo-to-template create option; AutoFlow “Send suggestion” inert label; Template detail “Set for later” mislabel (replaced with Close)
- Settings redesign (local-only): sectioned hub (Devices / Recording / Transcription & AI / API / Speakers / Templates / Automation / Appearance / Storage & privacy / About); Account/profile/avatar chrome removed; library top-bar person → settings gear; theme + language prefs; Devices (Wi‑Fi prefer + auto-sync watched folders); Recording default mic; Storage usage + delete-audio-after-process; library export + wipe

## Confirm only on owner’s real Plaud Note Pro

- Live BLE advertise `portVersion` / project 881, unlock with owner keys, battery notify
- Full multi-hour download resume + CRC
- On-device Parakeet/Whisper/Gemma quality + thermal throttling on physical silicon
- Whether FE10 alone works with E2EE off (no FE11) after one-time key import
- Wi-Fi SoftAP against real Note Pro hardware (JVM fake path is green)
