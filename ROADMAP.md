# Velum roadmap

Milestones are capability-based, not calendar estimates.

## M0 — Foundation (this repo bootstrap)

- [x] Gradle KMP project (Android / iOS / Desktop JVM)
- [x] `plaud-protocol` codec + unit tests from community fixtures
- [x] `ai` OpenAI-compatible client (`/models`, transcription, chat)
- [x] `ble` transport interface + Kable (mobile) / stub (desktop)
- [x] Compose shell: Device, Recordings (empty), Settings
- [x] Android Keystore AES-GCM secure store (API keys / device identity)
- [x] OSS hygiene: LICENSE, README, CONTRIBUTING, docs, CI
- [x] **Android-first process:** CI = JVM tests + Android assemble; iOS CI / Xcode deferred to M3

## M1 — Android BLE sync + transcription + templates + Markdown export

- Import device identity keys (one-time) into secure storage
- Protocol 20 unlock, handshake, list sessions (cmd 26)
- Download (28/29), decrypt `PLAUD.AI` → Ogg Opus, play
- Transcribe via user provider; chunk long audio
- Original Velum note templates + Markdown export
- Battery / basic device status

## M2 — Desktop meeting recorder

- Mic + system audio on separate channels (macOS, Windows first)
- Near-live chunk transcription
- Shared local data model with mobile
- Linux PipeWire path may slip to a later slice

## M3 — iOS

- Same core on TestFlight
- Background BLE restore / Companion-style UX as needed
- App Store later (acknowledged guideline risk)

## M4 — Sync (CRDT, E2E, user’s own cloud)

- Local-first SQLDelight + operation log / CRDT
- E2E blobs (XChaCha20-Poly1305, Argon2id passphrase)
- Pluggable storage: iCloud / Google Drive / OneDrive / WebDAV / S3
- Optional self-host relay later

## M5 — Integrations

- Google Calendar / Microsoft 365 (PKCE, minimal scopes)
- Notion, Slack, Zoom links, Obsidian/Markdown folder
- Action items → Todoist / Linear / Jira
- Webhooks + local MCP server exposing notes

## M6 — Speaker identification

- Diarization adapters (API + on-device sherpa-onnx)
- Desktop channel bias (mic = you)
- Calendar participant candidates + editable LLM suggestions
- Opt-in encrypted voice profiles (LGPD-aware “People” view)

## Stretch / research

- Protocol 30 (ECDH) if firmware migrates
- Wi-Fi fast transfer
- Desktop BLE (SimpleBLE)
- Live assist during meetings
