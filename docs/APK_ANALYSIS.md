# APK_ANALYSIS — Plaud Android app 3.30.0 (build 741)

How this was done: static analysis only. No Plaud server was contacted, there was no login, and nothing was sent to a device.
Tools:
- **REA** (`rea-agents`, `inspect-dart-aot` on Node 24): Dart snapshot, hash `1ce86630…`, partial string pool.
- **jadx 1.5.3** for the Java/Kotlin layer. I used it directly instead of REA's JADX path, which didn't run in this environment. It finished with 38 non-blocking errors.
- `strings` on the native libraries.

Proprietary code is not reproduced here; behavior is described in my own words. The APK contains hardcoded keys and tokens (Datadog/PostHog/Firebase API keys, Stripe/Shopify identifiers, internal TLS pins). They are **mentioned but not reproduced**.

Artifacts are in `apk/`: `jadx/sources`, `rea_dart.json`, `libapp.str`, `paths.txt` (1197 lines), `paths2.txt`, `action_map.txt`.

---

## 1. App structure

| Layer | What it is |
|---|---|
| Package | `ai.plaud.android.plaud`, minSdk 24, targetSdk 35, XAPK (base + arm64 split + config splits) |
| UI and business logic | **Flutter / Dart AOT** (`libapp.so`). This can't be decompiled; the inventory comes from the string pool. Packages `plaud_flutter_common` and `plaud_flutter` |
| Dart state and network | GetX (`get`), `dio` and `http`, `isar_community`, `hive`, `sqflite`, `flutter_secure_storage` |
| Editor and output | `appflowy_editor` (block editor), `markdown`, `markdown_webview` / `web_markdown_editor`, export to md/docx/pdf/txt/srt |
| Dart crypto | `pointycastle`, `cryptography` (ChaCha20-Poly1305, ECIES) |
| On-device ML | `onnxruntime` (libonnxruntime), `libvad_jni` (VAD) |
| Audio | libopus/opusJni, libjni_ogg, lame, mpg123, soundtouch, ffmpeg-kit, `libSoundPlus` (enhancement licensed by token via cmd 130) |
| Native device layer | Obfuscated Java/Kotlin: SDK `com.tinnotech.penblesdk` + `ui.*` (BLE), `vi.*` (Wi-Fi), `wi.*` (utils/crypto/OTA). Exposed to Dart via MethodChannel `action/...` in `FlutterDeviceManager` (map in `action_map.txt`) |
| Native storage | MMKV, Isar, DataStore |
| Telemetry and anti-tamper | Datadog, Firebase Crashlytics/Messaging, PostHog, `libtoolChecker` |
| Other | in_app_purchase, Stripe/Shopify (webview), sign_in_with_apple, inappwebview, Rive/Lottie |

Takeaway for Velum: everything that touches the device lives in the native layer, behind a small surface: connect, handshake, commands, file sync, Wi-Fi, OTA. Everything "smart" is in Dart, and almost all of it calls the server.

---

## 2. BLE protocol vs `PROTOCOL_RESEARCH.md`

Legend: ✅ confirmed · 🆕 new · ❌ contradicts. **Nothing in the APK contradicts the research.**

| Item | Status | APK detail |
|---|---|---|
| Service 0x1910, notify 0x2BB0, write 0x2BB1, CCCD 0x2902; battery 0x180F/0x2A19 | ✅ | Constants class `ui.x4` |
| protVersion < 20 plaintext, ≥ 20 pre-handshake | ✅ | The `ui.r3` agent checks the version after enabling notify |
| 0xFE10 (65040) sends `snSignature` (base64 → bytes) in 100 B chunks | ✅ 🆕 | Empty signature → `HANDSHAKE_FAIL`. 🆕 The signature comes from the server (`/device/sn-sign`) and is required **on every connection** |
| 0xFE11 (65041) = device asks for the public key | ✅ | If the last chunk gets this reply, the app sends FE12; otherwise it goes to the normal handshake |
| 0xFE12 (65042) sends the user's RSA public-key PEM in chunks (cmd, total, index, data) | ✅ 🆕 | 🆕 The PEM is the **user E2EE keypair** (`userRSAPublicKey`), not a device key |
| Device reply: RSA-wrapped blob (multi-packet, 4 B header per packet). RSA/ECB/PKCS1 with `userRSAPrivateKey` → key32 ‖ nonce12 ‖ aad12 ‖ ct, ct decrypts to "PLAUD.AI" | ✅ | `ui.y4` + `wi.m` |
| Link: ChaCha20-Poly1305 with **fixed** key, nonce and aad for the whole session; plaintext = `seq u32 ‖ packet`; seq ≤ last seen is dropped | ✅ | 🆕 **Writes are encrypted too**: the app increments its own seq, prefixes it as u32 and encrypts with the same key/nonce/aad before writing to 2BB1 |
| cmd 1 handshake with bind token, padded with '0' to 16 (or 32 if version ≥ 9) | ✅ 🆕 | Empty token → `UUID_IS_EMPTY`. 🆕 Variant "two_handshake" = cmd 1 + 8 B + optional length-prefixed string |
| delete 30 → reply 31 | ✅ | |
| 138 featureRequest | ✅ 🆕 | 🆕 The payload, after skipping 3 bytes, is a bitmap. **Bit 0x08 = device supports AES-GCM on Wi-Fi** (otherwise ChaCha20-Poly1305) |
| Wi-Fi: device joins a **WebSocket server run by the phone on :8081** | ✅ (confirmed for the current app, not just the Note) | `vi.t` opens `InetSocketAddress(8081)` with `org.java_websocket`. `vi.f1` connects the phone to the device's SoftAP through `WifiNetworkSpecifier` (Android 11+ `setSsidPattern`, Android 10 `setSsid`, WPA2) |
| Wi-Fi payload encryption | 🆕 | Every WebSocket frame is encrypted with the **same BLE session key/nonce/aad** (ChaCha20-Poly1305, or AES-GCM if bit 0x08), with its own seq. If BLE isn't up, the frame is dropped. **You need an active BLE session to use Wi-Fi** |
| OTA push | 🆕 | `wi.i` (OtaPushHelper) turns off a "push" flag for project codes **881/883** (Note Pro family). OTA uses cmds 112/114 → 113 and 143 → 143/144. Firmware comes from `/device/version/{model}` |
| Sync-in-idle Wi-Fi 120–125 (get/set SSID+password/list/test/result/delete), 116 → 117 disconnect | 🆕 | |
| SettingType codes (see the table at the end) | 🆕/✅ | 4 (AUTO_DELETE) and 26 (AUTO_SYNC) match the "never write" list |
| Commands that must never be sent: 104 clearAllFile, 65056 (0xFE20), 128 resetFindmy | ✅ | |

Command map (`qi.*`, cmd → reply): 1 handshake → 1/2; 3 state; 4 clock; 6 storage; 8 common settings; 9 battery; 10 Wi-Fi on/off; 20/21/22/23 rec start/pause/resume/stop; 24 → 33 get LED; 25 → 34 set LED; 26 file list; 28 sync → 28/29; 29 stop sync → 30; 30 delete → 31; 61 sync stats; 103 privacy; 107/108 name; 112/114 → 113 OTA; 116 → 117; 120–125 Wi-Fi config; 130 SoundPlus token; 131 SD CID; 138 features; 139/140 set/get common params; 143 → 143/144 OTA; 146 scan. Not interpreted: 5, 13, 38, 50, 51, 109, 141, 142, 145, 151.

SettingType: 1 BACK_LIGHT_TIME, 2 BRIGHTNESS, 3 LANGUAGE, 4 AUTO_DELETE_RECORD_FILE, 15 ENABLE_VAD, 16 REC_SCENE, 17 REC_MODE, 18 VAD_SENSITIVITY, 19 VPU_GAIN, 20 MIC_GAIN, 21 WIFI_CHANNEL, 22 SWITCH_HANDLER_ID, 23 AUTO_POWER_OFF, 24 SAVE_RAW_FILE, 25 AUTO_RECORD, 26 AUTO_SYNC, 27 FIND_MY, 30 VPU_CLK, 31 AUTO_STOP_RECORD, 32 BATTERY_MODE, 49 IBEACON_WAKEUP.

---

## 3. Server inventory → local replacement in Velum

Hosts: `api.plaud.ai` plus regional hosts (eu/jp/…; `.cn` for China) and test/dev/staging; `wss://realtime.plaud.ai/ws/transcribe`; `web/app/h5.plaud.ai`. Third parties: Stripe, Shopify, PostHog, Datadog, Firebase, Zendesk, voc.ai.

| Group / endpoint | In → Out (summary) | Velum: local replacement |
|---|---|---|
| `/auth/*` (access-token, refresh, otp, magic-link, SSO, MFA), `/user-app/*` | credentials → JWT | **Removed.** No account. Optional local app PIN/biometrics |
| `/user/*` profile, preference, settings, feature-access, membership; `/config/init|security|active-info`; `/onboarding/*` | → flags and preferences | Local DataStore/SQLDelight. Every feature is enabled; no feature-gating |
| **`/device/sn-sign`** | SN → `snSignature` (needed for FE10 on protVersion ≥ 20) | ⚠️ **Can't be generated locally** (signed with Plaud's key). **One-time import** of the value (from the official app's storage/logs or a debug capture), saved in Android Keystore. It is probably static per SN, but not proven |
| **`/device/bind`, `bind-token`, `apply-token`**, `/device/check`, unbind, state, `/device-app/device/list|update` | SN → bind token (cmd 1) | **One-time import** of the token. List, name and state are local (device DB) |
| `/device/apply-license` + SoundPlus license (cmd 130) | → enhancement token | Don't use SoundPlus. Local enhancement with RNNoise/DeepFilterNet (ONNX) or nothing |
| `/device/report` | telemetry | Removed |
| `/device/version/{model}` (OTA) | model/fw → firmware URL | Not done by default. Optional: user-supplied file, without automatic download |
| **`/e2ee/*`** (gen_key, auth, check_pin, open, reset_pin) | PIN → user RSA keypair (escrowed on the server) | ⚠️ If E2EE is on, the device asks for FE11/FE12 → **one-time import** of the RSA keypair (needs the PIN in the official app). Better: **turn E2EE off** in the official app before migrating, if the device then accepts the FE10 path alone (to be verified on hardware) |
| `/file/*` list/detail/presigned upload/download/S3 multipart/confirm_upload/version/export/share-url, `/filetag`, `/others/*` | audio → S3; metadata | Local storage (app files dir + Room/SQLDelight). Tags and folders local. Optional export/backup to SAF/Drive with no server |
| `/ai/transcript1`, `transsumm`, `trans-status`, `polish-transcation`, `recently_used_language` | audio → segments {start_time, end_time, speaker, content} | **whisper.cpp** (local, Opus decoded → 16 kHz PCM) with VAD (Silero ONNX). "Polish/cleanup" = optional local LLM pass |
| Diarization: `/speaker/list|sync`, speaker_embedding_task, `smart-speaker-tagging` | audio → embeddings/labels | Local embeddings (pyannote/WeSpeaker ONNX) + clustering. Speaker DB per user, kept local |
| `/ai/sum_new_note`, `retry_sum_note`, `gen_note_id`, `update_note_info`, `/summary/*` (generate-prompt, models/config, upload-image) | transcript + template_id + language + model → markdown | **On-device LLM** (llama.cpp / MediaPipe LLM, Gemma/Qwen ~2–4B) **or BYO API key** (OpenAI/Anthropic/Ollama on the LAN), configured by the user. Output in markdown |
| Templates: `/ai/customtemplates`, `chatllm` templates, `/summary` community templates (CRUD, favorites, recommend, search, categories) | → templates {template_id, name, icon/color, category, prompt, type, visibility, version} | Local templates (JSON/YAML in the app + user-editable). No "community"; import/export of files |
| `/ask/*` (extract_todos, key_data, conclusions, rewrite, skills, history, recommend questions), `ask_ai/workbuddy` | question + note → answer | Local chat over transcript + summary, same LLM backend. Skills = local prompts |
| `/ai/mark…` multimodal, `query_source`, label | photos/marks → highlights | Local: photos attached to the note plus timestamps from device marks. Multimodal only with a vision-capable LLM (optional) |
| `/gsearch/v1/search*` | query → results | Local FTS (SQLite FTS5) + optional embeddings |
| `/memoryos-api/v1/memory/*`, personalization, glossary | user facts | Local "memory" table and glossary injected into the prompt and Whisper (initial_prompt) |
| `/share/public|private/*` | → link | Share via the Android share sheet (md/pdf/txt/srt file). No hosted link |
| `/team-app/workspaces/*` | teams | Removed (out of scope) |
| `/calendar/sub|title`, Google Calendar connector, workflows (AutoFlow `/user/workflows`), connectors (Notion/Slack/Gmail), MCP & CLI | triggers → actions on the server | Local AutoFlow: rules "when recording ends / keyword → template X → export/share-intent". Calendar via Android CalendarProvider (title the recording after the current event). Integrations only through intents/files |
| `wss://realtime.plaud.ai/ws/transcribe`, `/transcription_realtime/get_language`, live caption/outlines | live stream → captions | Streaming whisper.cpp on device (latency permitting) |
| `/ws/notify`, Firebase Messaging | push | Removed; local notifications |
| `/marketing-app/*`, `/membership/stripe/*`, `/mem-app/v2/entitlements`, `/shopify/*`, in_app_purchase | quota and payment | **Removed.** No quota |
| Datadog, PostHog, Crashlytics | telemetry | Removed (local logs only) |

**Not possible locally:**
1. Generating `snSignature`, the bind token and the E2EE keypair (they need Plaud's signature or server-side state). Workaround: one-time import.
2. A firmware catalogue and OTA download.
3. Find My / Apple network registration (`find_my_token`).
4. The SoundPlus license.
5. Plaud's hosted links, teams, community and connectors.

---

## 4. Screens and features (Flutter modules)

Modules: splash, login, onboarding_guide, home, file_list, files_sidebar, files_search, file_detail (edit, editor, timeline, outline), record, template (mine, community, edit, share, search, categories, favorites), skill, chatbots / ask_ai, multimodal, explore, settings, account (personalization, glossary, my-summary-template, smart-speaker-tagging, account-workflow, webintegration, manage-memory, encryption-status, mfa, in-app-purchase), encryption (PIN, auto-auth), connectors (Google Calendar, Notion, Slack, Gmail, MCP & CLI), device_connect, device_binded_list, device_detail (auto-shutdown, battery, mic-gain, onsite/phonecall recording mode, rename, reset, storage, transfer BLE/Wi-Fi, transfer-cloud/PPC, findmy), device_update_ota_selector, cloud_setting, workspace_* (teams), membership/renewal/purchase, help_centre, markdown_webview, pd_webview, sound_editor, export, develop/* (debug).

Note flow:
1. Sync by BLE, or by Wi-Fi "fast transfer".
2. Transcribe: language (auto/list), speaker on/off, "transcript cleanup/polish".
3. Generate: a dialog with **template + language + model + speaker**. "Multi-summaries" are supported (several summaries per note).
4. Outline/timeline, ask AI, edit (appflowy block editor over markdown), speaker renaming.
5. Export: summary → md/docx/pdf/txt; transcription → txt/docx/pdf/srt, with or without timestamps.

Template format (inferred from DTO fields): `template_id`, `template_name`, `template_type`, `template_icon`, `template_icon_color`, `template_category`, `template_industry`, `prompt` / `prompt_type`, `template_visibility` (private/public), `template_version_id`, `template_set_for_next_use`, `used_template`, `note_summary_type`. The prompt is plain text from the user (or the system for built-in templates). The server turns it into an LLM prompt (`summary/generate-prompt`). The **output is markdown** (`ai_content` / `summary`, with `markdown`, `pre_markdown`, `delta_markdown` for incremental rendering), shown in the markdown webview/editor. Transcription = list of segments `{start_time, end_time, speaker, content}` (+ `speaker_map`, `original_speaker`, `embedding`). Velum suggestion: the same JSON for segments, and summary = `.md` with heading sections generated by the local template.

pt-BR strings exist inside libapp (and in the `config.pt` split) and can be reused as a UX reference. Rewrite the copy; don't copy it.

---

## 5. Device keys: where they come from and where they are kept

| Secret | Source | Use | Storage in the official app |
|---|---|---|---|
| `snSignature` | server `/device/sn-sign` (per SN) | FE10 on every connection, protVersion ≥ 20 | Dart side (persistent: secure storage/Isar), pushed to native with `action/sendGlobalData` (key, value) into an **in-memory map** of `ui.y4` |
| Bind token | `/device/bind` / `bind-token` / `apply-token` | cmd 1 handshake | Same path as above |
| `userRSAPublicKey` / `userRSAPrivateKey` (PEM) | E2EE flow (`/e2ee/gen_key`, unlocked by PIN through `check_pin`/`auth`/`open`; "auto-auth" keeps it on the device) | FE12 + decrypting the session secret | Same path (memory on native); persisted by Dart, probably in `flutter_secure_storage` |
| Session key/nonce/aad | Device, per connection | BLE and Wi-Fi | RAM only |

Velum: an "Import from Plaud" wizard that receives these 3–4 values **once**, stores them in the Android Keystore (encrypted), and never asks a server again. Verify on hardware: (a) whether snSignature is stable across connections and firmware versions; (b) whether, with E2EE off, the device never sends FE11.
