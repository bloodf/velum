# Velum desktop plan (research only)

**Status:** docs-only research for a future Mac + Windows desktop. **Do not start desktop implementation while Android remains first** (see [ROADMAP.md](../ROADMAP.md) M1 → M2). This note maps ideas from [anarlog](https://github.com/fastrepl/anarlog) and the [MeetingMint / offline note-taker Medium article](https://medium.com/data-science-collective/i-built-an-self-hosted-ai-meeting-note-taker-that-runs-100-offline-heres-how-you-can-too-d110b7ef0b95) onto Velum’s existing KMP modules.

**Studied revision:** shallow clone of `fastrepl/anarlog` (community MIT layer + `LICENSING.md` path split). Product docs under `docs/*.mdx` describe the shipped desktop UX.

---

## 1. License verdict (exact)

| Source | License | What Velum (Apache-2.0) may do |
| --- | --- | --- |
| anarlog paths **outside** `enterprise/**` | **MIT** ([LICENSE](https://github.com/fastrepl/anarlog/blob/main/LICENSE), [LICENSING.md](https://github.com/fastrepl/anarlog/blob/main/LICENSING.md)) | **May copy, modify, merge, and redistribute code**, including substantial portions, **if** the MIT copyright notice and permission text are preserved (typically via `NOTICE` / `THIRD_PARTY` and file headers). Apache-2.0 may combine MIT-licensed material; MIT portions keep their notices. |
| anarlog **`enterprise/**`** | **Commercial** ([enterprise/LICENSE](https://github.com/fastrepl/anarlog/blob/main/enterprise/LICENSE)) | **Must not copy, vendor, or redistribute.** No production use of that source without a Fastrepl agreement. Treat as closed for Velum: **ideas only after clean-room**, and prefer not to study implementation detail there at all. |
| Moving code across anarlog’s `enterprise/` boundary | Path-based (LICENSING.md) | A file does **not** keep MIT merely because it once lived outside `enterprise/`. Never import anarlog enterprise trees into Velum. |
| Medium article / MeetingMint Flask write-up | Article prose + paid/App Store product | **Architecture inspiration only.** The article is not a license grant. Do **not** paste article code into Velum. The described Flask+Whisper+Ollama pipeline is a common pattern Velum already largely owns in Kotlin (`RecordingProcessor`, BYO Ollama/LM Studio). |
| Third-party crates / models anarlog depends on | Their own licenses (see §4) | Usable **directly from upstream** when permissive (MIT / Apache-2.0 / Unlicense / dual). Do not assume anarlog’s MIT covers those dependencies’ notices. |

### Practical recommendation for Velum

1. **Reuse policy (owner):** copy open-source code aggressively into Velum modules when it speeds desktop work (MIT/Apache/BSD **and** GPL/AGPL if useful). Keep original copyright headers; list paths in [THIRD_PARTY.md](../THIRD_PARTY.md). Prefer KMP/Compose over Tauri UI shell, but vendor anarlog MIT crates/algorithms freely when useful (still avoid `enterprise/**` commercial tree unless listed).
2. **Do not** copy TipTap editor UI, brand assets, or Anarlog Pro billing/share flows by default; Velum already has Compose notes + planned user-cloud sync (M4).
3. Owner accepts sorting licenses later; keep THIRD_PARTY.md current.

---

## 2. What anarlog is (architecture snapshot)

Local-first “Granola without a bot”: capture **mic + system audio** on-device, optional local or BYO STT/LLM, notes in **SQLite + files**, cloud optional.

| Layer | Stack |
| --- | --- |
| Desktop shell | Tauri v2 — React/TS UI, Rust backend (`apps/desktop`) |
| Capture / STT / detect | Rust `crates/*` + Tauri `plugins/*` |
| Shared TS | `packages/*` (editor, db, plugin SDK, UI) |
| Optional hosted | `apps/api`, Supabase auth/sync/billing |
| Enterprise | `enterprise/**` — commercial workers / control plane (out of bounds) |

**AI split (product lesson Velum already mirrors):** Transcription ≠ Intelligence. Each is independently Local / BYO / hosted.

**Platform notes from anarlog docs:**

- System audio + mic on Mac / Windows / Linux (Linux PipeWire/Pulse).
- Meeting auto-start from calendar; mic-activity detection (Mac/Linux stronger; Accessibility); stop when mic released.
- On-device STT on Apple Silicon (Soniqo/Parakeet, Apple Speech, whisper.cpp `.bin`); Windows leans hosted or BYO more heavily in current docs.
- Local Intelligence via Ollama / LM Studio / Unsloth; Apple Intelligence experimental.
- Templates + Auto prompt customization; memos / summary / transcript views; Markdown/PDF/Org export.
- Calendar: Apple (macOS), Google, Outlook (read-only).
- Opt-in E2E CloudSync (Pro); local MCP/CLI for agents.
- Plugins = Tauri capability crates (STT, calendar, detect, tray, export, …) — not a user-facing extension store in the OSS sense.

---

## 3. Medium article (MeetingMint) — takeaways only

Offline Flask app: upload audio → **pydub chunking** → local **Whisper** → **Ollama** meeting minutes → poll status UI. Later productized as a Mac App Store app.

**Reuse as ideas (not code):**

- Explicit offline path: download models *before* disconnecting.
- Chunk long audio before ASR (Velum already map-reduces summaries; desktop should keep chunked ASR).
- Status surface for long jobs (Velum: WorkManager / desktop job queue).
- BYO local LLM HTTP (`/api/generate` / OpenAI-compatible) — already first-class in Velum Settings.

**Do not reuse:** Flask/Python server as Velum’s desktop architecture; paid MeetingMint sources; article snippets without a clear OSS grant.

---

## 4. Permissive libraries / crates usable directly

These are **upstream** dependencies anarlog uses (or equivalents Velum can use). They are **not** “anarlog code”; pull from crates.io / GitHub with their notices.

| Crate / library | License (crates.io / project) | Velum desktop use |
| --- | --- | --- |
| **cpal** | Apache-2.0 | Cross-platform PCM I/O; candidate for JVM/native bridge or Rust helper binary |
| **wasapi** | MIT | Windows loopback / device capture reference |
| **cidre** | MIT | macOS AV / Accessibility bindings (if any Rust sidecar) |
| **objc2** (+ AppKit / ApplicationServices) | MIT | macOS permissions / AX helpers |
| **windows** (windows-rs) | MIT OR Apache-2.0 | Win32 capture / UI Automation |
| **rodio**, **dasp**, **ringbuf** | MIT OR Apache-2.0 | Resample / ring buffers |
| **ebur128** | MIT | Loudness / level meters |
| **hound** | Apache-2.0 | WAV read/write |
| **sysinfo** | MIT | Process list for meeting-app detection |
| **xcap** | Apache-2.0 | Screen/window capture (optional; not required for system-audio) |
| **pipewire** / **libpulse-binding** | MIT | Linux later (ROADMAP: may slip) |
| **atspi** | Apache-2.0 OR MIT | Linux assistive-tech meeting-chat (stretch) |
| **whisper-rs** | Unlicense | whisper.cpp bindings (Unlicense is public-domain-like; keep attribution courtesy) |
| **whisper.cpp** | MIT | Same family as Velum’s Whisper models |
| **sherpa-onnx** | Apache-2.0 | **Already Velum’s** local ASR/diarization path — prefer extending JVM/desktop natives over new stacks |
| **onnxruntime** | MIT | Diarization / embeddings (Velum catalog already) |
| **tantivy** | MIT | Full-text search (optional desktop index; Velum has `core/search`) |
| **tauri** | Apache-2.0 OR MIT | Usable, but **not recommended** as Velum shell (Compose Desktop already exists) |

**anarlog’s own MIT crates** (`meeting-capture`, `audio-actual`, `detect`, `whisper-local`, `calendar`, `cloudsync`, `template-app`, …): **legally copyable with MIT notice**, but **prefer behavioral reimplementation** into `ai` / `core` / `composeApp` `desktopMain` so Velum stays one KMP codebase. Do **not** vendor `enterprise/*` workers (Zoom RTMS, Google Meet, control-plane, etc.).

---

## 5. Feature map → Velum KMP modules

| Anarlog / article feature | Velum home | Exists today? | Reuse posture |
| --- | --- | --- | --- |
| Mic capture | `composeApp` platform + `ai` pipeline | Android mic FGS yes; **desktop `LivePcmCapture` = null** | Reimplement; ideas from anarlog capture UX |
| System-audio / loopback (Mac+Win) | New `expect`/`actual` in `ai` or `core` + JNI/Rust sidecar | No (M2) | **Ideas + optional MIT algorithm copy**; prefer cpal/wasapi/ScreenCaptureKit/WASAPI loopback via thin native |
| Dual-channel (mic vs meeting) | `ai` diarization + channel bias (ROADMAP M6) | Partial (profiles on Android) | Ideas; Velum channel-bias design already sketched |
| Meeting auto-detect (mic activity / app list) | `core/autoflow` + desktop detect service | AutoFlow for post-process exists; **no OS mic watch** | Reimplement heuristics; anarlog `detect` is MIT if copying |
| Calendar start / stop | `core/calendar` | Android calendar hooks / AutoFlow insert | Extend for desktop EventKit / Outlook / Google PKCE (M5) |
| Local STT | `ai/local` (sherpa, tiers, SHA downloads) | Android yes; JVM fake/partial | Extend desktop natives; **do not** fork anarlog Soniqo stack |
| Local / BYO LLM | `ai` OpenAI-compatible + LiteRT/llama | Yes (Android local + BYO) | Already aligned with Medium/Ollama pattern |
| Note editor (memos / summary / transcript) | `composeApp` UI + `core` models | Compose Markdown notes yes | Keep Compose; do not port TipTap |
| Templates | `core/templates` + importer | Built-ins + Plaud-attributed + JSON import | Already ahead for Velum’s template story |
| Dictionary / glossary | `ai` glossary injection | Yes | Keep |
| Export MD/PDF | `composeApp` / `core` | Markdown share yes; PDF later | Ideas |
| Plugins / MCP / CLI | ROADMAP M5 webhooks + local MCP | Not yet | Ideas from anarlog CLI/MCP skill; implement Velum-native |
| Sync E2E | ROADMAP M4 | Not yet | **Ideas only** for product shape; do not copy Anarlog Pro CloudSync / Supabase |
| Voice profiles / diarization | `ai` `VoiceProfileStore` | Android encrypted profiles yes | Reuse Velum; anarlog `voiceprint`/`pyannote-local` MIT if needed |
| Tray / floating bar | `composeApp` desktop | No | Ideas |
| Insights / badges | — | No | Low priority; skip or invent later |
| Hosted share links / billing | — | Out of scope (no Velum server) | Ignore |
| Bot-free capture (no Zoom bot) | Desktop capture design | M2 | Same product principle |

---

## 6. Phased plan (capability order, Android still first)

Phases are **capability slices**, not calendar estimates. Ship Android M1 items before spending implementation on these.

### Phase D0 — Research freeze (this doc)

- [x] License boundary MIT vs `enterprise/`
- [x] Feature → module map
- [x] Permissive upstream crate list
- [ ] Owner ack: desktop stays behind Android until M1 gate

### Phase D1 — Desktop audio foundation (ROADMAP M2 start)

- `LivePcmCapture` desktop: mic PCM → same WAV → `RecordingProcessor` path as Android.
- System-audio loopback **macOS** (ScreenCaptureKit / AVAudioEngine tap) and **Windows** (WASAPI loopback), separate channels.
- Permission UX: mic, system-audio (macOS TCC), Windows privacy settings.
- No Tauri; Compose Desktop window + optional tiny native helper licensed Apache-2.0/MIT.

### Phase D2 — Near-live STT on desktop

- Wire JVM/desktop sherpa-onnx or whisper.cpp with existing `LocalAsrRouter` / `DeviceComputeClass`.
- Chunked after-recording path first; streaming when packs Ready.
- Reuse model download + SHA-256 catalog (no APK-size concerns on desktop → allow larger tiers).

### Phase D3 — Meeting detection + calendar

- Process / mic-activity heuristics → “Start recording?” notification (opt-in).
- Calendar-backed auto-start using `core/calendar` + desktop providers (Apple / Google / Outlook PKCE).
- Align with existing AutoFlow for post-meeting actions.

### Phase D4 — Desktop UX parity with phone notes

- Tray icon, pause/resume, markers, input device picker.
- Same memos / summary / transcript / templates / voice-profile screens on `desktopMain`.
- File import already stubbed (`AudioImporter.desktop`) — flesh out to match Android import hub.

### Phase D5 — Sync + integrations (ROADMAP M4–M5)

- Local-first SQLDelight + E2E user-cloud (not Anarlog Pro).
- MCP/CLI exposing Velum notes (inspired by anarlog’s agent skill, clean-room API).
- Linux PipeWire as follow-on if Mac/Win solid.

### Explicit non-goals (near term)

- Porting anarlog UI/brand or TipTap editor.
- Vendoring `enterprise/**`.
- Velum-hosted sync/billing.
- Pausing Android work for desktop.

---

## 7. Top features worth taking (ideas rank)

1. **Bot-free mic + system-audio capture** with dual channels (core M2).
2. **Transcription vs Intelligence as separate Local/BYO toggles** (Velum already; keep strict on desktop).
3. **Calendar-triggered record + mic-activity nudge** (detection without forcing auto-record).
4. **Offline-ready model downloads** before sensitive meetings (Medium + anarlog offline doc).
5. **Local SQLite + filesystem recordings + Markdown export** (local-first, auditable).
6. **Templates + editable memos/summary/transcript** with speaker reassignment.
7. **Opt-in sync only**; API keys stay device-local.
8. **Agent surface (CLI/MCP)** for notes — after core capture works.
9. **Floating/tray controls** during calls.
10. **Dictionary / custom terms** for ASR (Velum glossary — keep).

---

## 8. Provenance checklist (when implementation starts)

Before any anarlog MIT file is copied:

1. Pin git revision + path list.
2. Confirm path ∉ `enterprise/`.
3. Add copyright + MIT text to `NOTICE` / `THIRD_PARTY`.
4. Prefer porting logic into Kotlin; if Rust stays, isolate under an Apache-2.0/MIT helper with clear NOTICE.
5. Re-run license scan on transitive natives (ffmpeg, onnx, etc.).

---

## References

- anarlog README, `LICENSING.md`, `LICENSE`, `enterprise/LICENSE` (studied clone).
- anarlog product docs: meetings, automatic-capture, models-and-providers, offline, calendar, notes, sync, customize-summaries.
- Medium: [self-hosted offline AI meeting note taker](https://medium.com/data-science-collective/i-built-an-self-hosted-ai-meeting-note-taker-that-runs-100-offline-heres-how-you-can-too-d110b7ef0b95) (MeetingMint / Flask+Whisper+Ollama).
- Velum: [ROADMAP.md](../ROADMAP.md), [ARCHITECTURE_AI.md](ARCHITECTURE_AI.md), [LEGAL.md](LEGAL.md), [STATUS.md](STATUS.md).
