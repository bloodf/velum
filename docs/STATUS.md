# Velum status

Owner standing rule: nothing may remain stub/demo-only. Prefer **blocked-on-owner** over fake success.

## Working

### Protocol / BLE
- [x] Protocol-20 framing, unlock (FE10/FE11/FE12), handshake, list (26), download (28/type-2/29), battery (9)
- [x] Forbidden opcodes never sent: **5, 104, 128, 0xFE20, 143 (OTA)**; never write settings **4 / 26**
- [x] Unused cmds left alone: 5, 13, 38, 50, 51, 109, 141, 142, 145, 151
- [x] Fixed session key/nonce/aad; plaintext = `seq ‖ packet`; FE10 snSignature on every connect (prot ≥ 20)
- [x] Cmd 138 feature bit 0x08 (AES-GCM Wi-Fi); SoftAP + WS `:8081` transfer skeleton
- [x] Project **881/883** Note Pro family (no OTA)
- [x] Per-model command tables (`DeviceModel` / `CommandTable`)
- [x] `PlaudDeviceClient` + JCA `SessionCipher` / `FileDecryptor` (Android + JVM)
- [x] `FakeBleTransport` / `FakePlaudPeripheral` + JVM E2E unlock→download→decrypt (rejects empty FE10)
- [x] Device screen: scan / connect / sessions / battery when identity keys imported
- [x] Static analysis notes: `docs/APK_ANALYSIS.md`

### Audio / AI
- [x] Ogg demux + Opus decode (kopus) → PCM
- [x] OpenAI-compatible API: URL / key / model list refresh / transcribe / chat (BYO URL only; no Plaud hosts)
- [x] Per-task Local vs API settings (mix allowed)
- [x] `RecordingProcessor`: decode → ASR → diarize → Markdown transcript → summarize → DB
- [x] Glossary + memory injected into ASR `initial_prompt` / LLM system prompts
- [x] Map-reduce summarization for long transcripts
- [x] LocalModelManager: real Ktor download + archive extract + disk Ready checks
- [x] ASR catalog URLs (Parakeet / Whisper turbo / SenseVoice from k2-fsa)
- [x] Android sherpa-onnx `OfflineRecognizer` (Parakeet / Whisper / SenseVoice)
- [x] `StreamingTranscriber` (Android chunked OfflineRecognizer; JVM `FakeStreamingTranscriber` test double)
- [x] Local diarization + cosine voice profiles (`VoiceProfileStore`)
- [x] Local LLM LiteRT-LM on Android when Gemma Ready
- [x] Ask AI on recording notes via local/`AiTaskRouter` LLM (no Plaud ask endpoints)
- [x] Compose Markdown preview for Fontes / Notas
- [x] Generate sheet + retranscribe launch processor
- [x] AutoFlow matcher + runner on matching recordings
- [x] AutoFlow post-actions: share Markdown intent / calendar ACTION_INSERT
- [x] CI E2E: Fake BLE → mock API ASR/LLM → Markdown in DB (`DeviceToSummaryE2ETest`)

### Local data / search / templates
- [x] SQLite FTS5 over recording title+transcript+summary and templates (raw `SqlDriver` DDL/MATCH — SQLDelight dialect SOEs on FTS5); Files/Templates search uses FTS with `ListSearch` fallback
- [x] Glossary + memory tables in `Velum.sq`; CRUD under Personalization / Memory
- [x] Local JSON/Markdown templates create/edit persist locally (community/cloud templates removed)
- [x] CalendarProvider read current/next event title to name recordings after sync (permission-denied reported clearly)

### App surfaces
- [x] Search (Files / Templates)
- [x] Share / export Markdown (Android share sheet; desktop clipboard/file)
- [x] Trash + move-to-folder
- [x] FAB start recording (insert row) + import audio → processor
- [x] Find/replace on transcript
- [x] Preferences / personalization / languages persist
- [x] Key import UI (Settings → paste serial + snSignature + bind token + optional RSA PEMs → Keystore)
- [x] Integrations: local device calendar (no Plaud/Google OAuth server path required)
- [x] Roborazzi phone screenshots (~1080×2400) in CI artifacts
- [x] Instrumented Compose UI smoke tests (`MainFlowUiTest`)
- [x] Signed debug APK (Android debug keystore) as CI artifact
- [x] UI comparison vs owner Plaud reference zip → `docs/UI_COMPARISON.md`

## In progress

- [ ] WorkManager + thermal monitoring for multi-hour on-device jobs
- [ ] First-run UX guiding model downloads
- [ ] Mic capture while “Start recording” (row is created; live PCM capture next)
- [ ] Mind-map renderer (CTA present; summary path covers notes)
- [ ] Android OnlineRecognizer (true low-latency stream) when model packs support it

## Blocked on owner

- [ ] **One-time device identity import** (`snSignature` / `sn-sign` + bind token + optional RSA key pair) from official Plaud app storage/logs — **cannot be generated locally** (Plaud-signed). Import UI is ready; values must come from the owner.
- [ ] Download URLs (+ SHA-256) for pyannote / WeSpeaker / TitaNet / Gemma / Qwen weights
- [ ] Confirm Ogg Opus rate/channels + PLAUD.AI decrypt against a real Note Pro capture
- [ ] Product default Local vs API after first-run download UX is agreed
- [ ] Grant READ_CALENDAR on device to enable calendar naming / insert intents (runtime permission)

## Removed (server inventory → local)

- Auth / billing / membership / Stripe / Shopify / telemetry (Datadog, PostHog, Crashlytics, Firebase Messaging)
- Plaud host URLs (`api.plaud.ai`, regional, `wss://realtime.plaud.ai`, …) — not present in app code; BYO OpenAI-compatible base URL only
- Community/cloud templates, hosted share links, teams, OTA catalogue, SoundPlus license

## Confirm only on owner’s real Plaud Note Pro

- Live BLE advertise `portVersion` / project 881, unlock with owner keys, battery notify
- Full multi-hour download resume + CRC
- On-device Parakeet/Whisper/Gemma quality + thermal throttling on physical silicon
- Whether FE10 alone works with E2EE off (no FE11) after one-time key import
- Wi-Fi fast path (explicitly out of scope until approved)
