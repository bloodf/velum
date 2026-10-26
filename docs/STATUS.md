# Velum status

Owner standing rule: nothing may remain stub/demo-only. Prefer **blocked-on-owner** over fake success.

## Working

### Protocol / BLE
- [x] Protocol-20 framing, unlock (FE10/FE11/FE12), handshake, list (26), download (28/type-2/29), battery (9)
- [x] Forbidden opcodes never sent: **5, 104, 0xFE20, 143 (OTA)**
- [x] Per-model command tables (`DeviceModel` / `CommandTable`)
- [x] `PlaudDeviceClient` + JCA `SessionCipher` / `FileDecryptor` (Android + JVM)
- [x] `FakeBleTransport` / `FakePlaudPeripheral` + JVM E2E unlock→download→decrypt
- [x] Device screen: scan / connect / sessions / battery when identity keys imported

### Audio / AI
- [x] Ogg demux + Opus decode (kopus) → PCM
- [x] OpenAI-compatible API: URL / key / model list refresh / transcribe / chat
- [x] Per-task Local vs API settings (mix allowed)
- [x] `RecordingProcessor`: decode → ASR → diarize → Markdown transcript → summarize → DB
- [x] Map-reduce summarization for long transcripts
- [x] LocalModelManager: real Ktor download + archive extract + disk Ready checks
- [x] ASR catalog URLs (Parakeet / Whisper turbo / SenseVoice from k2-fsa)
- [x] Android sherpa-onnx `OfflineRecognizer` (Parakeet / Whisper / SenseVoice)
- [x] Local diarization + cosine voice profiles (`VoiceProfileStore`)
- [x] Local LLM LiteRT-LM on Android when Gemma Ready
- [x] Compose Markdown preview for Fontes / Notas
- [x] Generate sheet + retranscribe launch processor
- [x] AutoFlow matcher + runner on matching recordings
- [x] CI E2E: Fake BLE → mock API ASR/LLM → Markdown in DB (`DeviceToSummaryE2ETest`)

### App surfaces
- [x] Search (Files / Templates)
- [x] Share / export Markdown (Android share sheet; desktop clipboard/file)
- [x] Trash + move-to-folder
- [x] FAB start recording (insert row) + import audio → processor
- [x] Find/replace on transcript
- [x] Preferences / personalization / languages persist
- [x] Integrations OAuth-ready; missing client IDs show **blocked-on-credentials** (pt-BR + EN)
- [x] Roborazzi phone screenshots (~1080×2400) in CI artifacts
- [x] Instrumented Compose UI smoke tests (`MainFlowUiTest`)
- [x] Signed debug APK (Android debug keystore) as CI artifact
- [x] UI comparison vs owner Plaud reference zip → `docs/UI_COMPARISON.md`

## In progress

- [ ] WorkManager + thermal monitoring for multi-hour on-device jobs
- [ ] First-run UX guiding model downloads
- [ ] Mic capture while “Start recording” (row is created; live PCM capture next)
- [ ] Mind-map renderer (CTA present; summary path covers notes)

## Blocked on owner

- [ ] OAuth client IDs / redirect URIs for Google Calendar (and other integrations)
- [ ] Download URLs (+ SHA-256) for pyannote / WeSpeaker / TitaNet / Gemma / Qwen weights
- [ ] One-time device identity import (`sn-sign` signature + RSA key pair + bind token) from Plaud account
- [ ] Confirm Ogg Opus rate/channels + PLAUD.AI decrypt against a real Note Pro capture
- [ ] Product default Local vs API after first-run download UX is agreed

## Confirm only on owner’s real Plaud Note Pro

- Live BLE advertise `portVersion` / project 881, unlock with owner keys, battery notify
- Full multi-hour download resume + CRC
- On-device Parakeet/Whisper/Gemma quality + thermal throttling on physical silicon
- Wi-Fi fast path (explicitly out of scope until approved)
