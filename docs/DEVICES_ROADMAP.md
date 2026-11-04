# Multi-device recorder roadmap

Research date: 2026-10-27. Goal: support other famous Bluetooth AI note-takers / wearables that have **open-source or well-documented community protocols**, via a pluggable [`RecorderDriver`](../recorder/src/commonMain/kotlin/dev/velum/recorder/RecorderDriver.kt).

**Reuse policy (owner):** copy open-source code aggressively into Velum modules when it helps (MIT/Apache/BSD/ISC/Unlicense/MPL **and** GPL/AGPL if useful). Keep each file’s original copyright/license header. Record every copy in [THIRD_PARTY.md](../THIRD_PARTY.md). Owner accepts sorting licenses later.

---

## Ranked feasibility

| Rank | Device / project | OSS / docs | License | Local transfer w/o vendor cloud | Codec | Auth | Feasibility for Velum | Status in repo |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **Plaud** (Note / NotePin / Note Pro / One) | Community RE + Velum | Research notes MIT; firmware proprietary | **Yes** (BLE + SoftAP WS) | Ogg Opus / Opus / ADPCM | FE10 snSignature + session AES | **Shipped** | `PlaudRecorderDriver` |
| 2 | **Omi / Friend** ([BasedHardware/omi](https://github.com/BasedHardware/omi)) | Official protocol docs + firmware | **MIT** | **Yes** (live BLE stream) | Opus 16 kHz (default), PCM, μ-law | Optional DIS; no cloud unlock | **High — driver registered, not usable in the app yet** (registry name match and packet decode only; the scanner does not list it yet, and a connected non-Plaud recorder shows an "unsupported" message in `DeviceViewModel`; hardware unverified) | `OmiRecorderDriver` + packet fixtures |
| 3 | **Pocket** + **OpenPocket** DIY ([pocket-libre](https://github.com/shahcolate/pocket-libre), [open-pocket](https://github.com/TanskiSzymon/open-pocket)) | PROTOCOL.md + MIT client; **third-party reverse engineering** from HCI captures of the vendor app (shahcolate/pocket-libre; its author states a DMCA basis for the work, unchecked here). **Under the owner's RE-derived-work criterion**, same as Limitless, not final | **MIT** (client/scripts); HW CERN-OHL-P | **Yes** (BLE MP3 + Wi‑Fi) | MP3 16 kHz mono ~32 kbps | 16-char session key (`APP&SK&`) | **High for the protocol; driver registered, not usable in the app yet** (registry name match only; the scanner does not list it, same `DeviceViewModel` gate as Omi; hardware unverified) | `PocketRecorderDriver` + framing tests |
| 4 | **Limitless Pendant** (omi `LimitlessProtocol.kt` + [pendant-cli](https://github.com/MAkcanca/pendant-cli)) | MIT codec in-tree; community clients derived from the closed Limitless app (see #20 section) | MIT for the in-tree codec and MAkcanca/pendant-cli; sdelcore/pendant-cli has no formal license (personal/research only) | **Yes** (BLE flash drain) | Opus in flash pages | Unreconciled: ECDH + AES-GCM (MAkcanca) vs X25519 + ChaCha20-Poly1305 (sdelcore), see #20 section | Medium: protocol exists but is RE-derived; has writes, needs its own guard (#18) | `LimitlessProtocol` vendored; driver later |
| 5 | **OpenGlass / OmiGlass** ([OpenGlass](https://github.com/BasedHardware/OpenGlass) → omi) | MIT firmware; audio PR uses Omi-like BLE | **MIT** | Yes (Opus BLE) | Opus | Same family as Omi | Would reuse the **Omi driver** (itself not usable in the app yet) | Via Omi UUIDs, registry name match only |
| 6 | **DIY XIAO / ESP32 wearing Friend firmware** | Omi open firmware | MIT | Yes | Opus/PCM | Same as Omi | **Same as Omi** (not usable in the app yet) | Registry name match only; the scanner does not list it yet |
| 7 | **whistle-esp32** ([andrisgauracs/whistle-esp32](https://github.com/andrisgauracs/whistle-esp32)) | On-device Whistle ASR demo | **Apache-2.0** | N/A — **no phone BLE audio transfer** | Local ASR only | N/A | **Not a RecorderDriver** | Documented only |
| 8 | **Bee (Amazon)** | bee-cli (cloud) plus one community BLE spec, [cewinharhar/bee_local](https://github.com/cewinharhar/bee_local), decompiled from one APK version (#20 third pass) | bee-cli MIT; bee_local says MIT but has no license file | BLE spec exists, single unverified source | OPUS or AAC per that spec | Pairing mode; no encryption section in the spec | Low: one source, not independently verified | Watch (no-go for now, #20 third pass) |
| 9 | **Soundcore Work** (Anker D3200, Feishu "recording bean") | Community protocol notes ([tacshi/Soundcore](https://github.com/tacshi/Soundcore) `PROTOCOL.md`) plus two clients: MIT browser client [recording-bean-web](https://github.com/Shawn-TKD/recording-bean-web) and MIT Swift port [soundcore-pull](https://github.com/knorthfield/soundcore-pull) | MIT clients; the protocol notes repo has no license file | Yes over BLE; Wi-Fi SoftAP bulk | Ogg/Opus | ECDH/HKDF session, AES file decryption; no account in the clients | **Go as community-RE** (same policy as #18), writes guarded | Driver task opened: #432 |
| 10 | **Viaim / Mobvoi TicNote / Sandbar / iFlytek·DingTalk A1** | Viaim, TicNote, Sandbar: cloud or marketing only. DingTalk A1: MIT client [dingtalk-a1-pc-tools](https://github.com/Shawn-TKD/dingtalk-a1-pc-tools) needing a per-device secret from the owner's DingTalk account | Mixed | Vendor cloud typical; A1 over BLE with account-derived secret | Opus in a DTYJ container (A1) | Vendor account (A1) | Low | No-go on current evidence, per-device rows in the #20 section below |
| 11 | **Rewind / Limitless cloud apps** | Not device BLE OSS | Proprietary | Cloud-first | — | Account | Out of scope | — |
| 12 | **Compass** (wearable note brands) | No public GATT docs found | Proprietary | Unknown | Unknown | Vendor | Low | Revisit if RE appears |

---

## Per-device notes

### Plaud (shipped)

- GATT `1910` / `2BB0` notify / `2BB1` write; SoftAP WebSocket `:8081`.
- Driver: `PlaudRecorderDriver` → existing `PlaudDeviceClient` + `DeviceCapabilityRegistry`.
- Cloud never required for sync once identity keys are imported.

### Omi / Friend (implement)

- Service `19B10000-E8F2-537E-4F6C-D104768A1214`
  - Audio `…0001`, Codec `…0002` (+ light codec `…0003` in some SDKs)
- Packet: 2-byte LE packet number + 1-byte fragment index + payload; reassemble then decode Opus/PCM.
- Battery: standard `180F` / `2A19`.
- Mode: **live stream** (list/download N/A or future batch firmware).
- Docs + code from BasedHardware/omi (MIT) live under `recorder/.../omi/` — see THIRD_PARTY.md.

### Pocket / OpenPocket (protocol next)

- ASCII `APP&…` / `MCU&…` on service `001120A0`; MP3 on `001120A1`.
- Session key required; first key after hardware reset pairs the device.
- pocket-libre MIT — credit PROTOCOL.md; reimplement commands in Kotlin. The protocol comes from HCI captures of the vendor app (third-party RE), so whether to build a command driver on it is pending the owner's RE-derived criterion (#20 section); the read-only profile (#274) sends nothing.

### Limitless Pendant (later)

- Protobuf over dual characteristics; flash-page Opus. Public clients exist (MAkcanca/pendant-cli, MIT; sdelcore/pendant-cli, personal/research only), both derived from the closed Limitless app (#20 section).
- Building on RE-derived work is an owner decision; heavy surface, has writes (own guard first, #18).

### whistle-esp32 (not a recorder target)

- Runs Whistle ASR **on the MCU**; button → local transcript / LED.
- No documented BLE GATT for dumping meeting audio to a phone.
- Useful as inspiration for on-device ASR, not as `RecorderDriver`.

### Bee / Soundcore / Viaim / TicNote / Sandbar / iFlytek A1

- No permissive, complete public BLE audio protocol suitable for Apache-2.0 reimplementation found in this pass.
- Bee post-Amazon: developer CLI/API; one community BLE spec from a decompiled APK exists (#20 third pass), single source and unverified, so not driver-ready.
- Soundcore Work (D3200): go as community-RE, see the third pass in the #20 section; the driver task is tracked in the issue linked there.

---

## Closed note-takers: feasibility re-check (#20, 2026-11-03)

Public sources only: vendor pages, press, package registries and public repositories. Nothing here was captured,
decompiled or probed by Velum.

**Proposed go criterion (the owner sets this):** a vendor-documented or openly licensed protocol, independently
verified, that a driver could be built on. This proposal deliberately treats protocols derived from reverse-engineering
a closed app as an owner decision rather than automatic go or no-go, because Plaud (row 1) is itself community-RE'd and
#18 accepts community RE. Decisions below that depend on this criterion (marked "pending criterion") are not final.

| Device | What public sources show | Decision |
| --- | --- | --- |
| **Bee (Amazon)** | The developer path is a cloud one: [bee-computer/bee-cli](https://github.com/bee-computer/bee-cli) (MIT, under the `bee-computer` organization) exports conversations, summaries, facts and todos as Markdown or JSON and runs as an MCP server; it needs the Bee app with Developer Mode and a Bee login. A community repo, [cewinharhar/bee_local](https://github.com/cewinharhar/bee_local) (GitHub shows no license field; the README says MIT; 3 commits, last push 2026-02-05), drives the Bee Pioneer over BLE and documents a protocol. Its README lists an `archive/` of "APK decompilation" research artifacts, so the spec probably comes from the closed app (inference: the README does not say how it was obtained); it is single-source and unverified. Earlier notes report encrypted streams on newer firmware; not re-verified. | **No-go for now (BLE half pending criterion).** The cloud path needs an account (outside the product rules). The BLE path rests on one small repo with no GitHub license field; whether RE-derived work is acceptable is the owner's call. Revisit if the criterion allows it or a verified spec appears. |
| **Soundcore Work** (Anker) | Product pages and press only. Wi-Fi and Bluetooth transfer, Apple MFi on iOS ([audioXpress](https://audioxpress.com/news/soundcore-launches-ai-voice-recorder-for-instant-transcription-and-summarization)); the vendor's German product page claims TLS on the Bluetooth and app link ([Soundcore DE](https://www.soundcore.com/de/collections/ai-voice-recorder)). An earlier note that stored data is AES-256 encrypted could not be confirmed in the cited UDN article and is dropped. No repository, protocol write-up or SDK found. | **No-go.** Nothing public found to build on; vendor-claimed transport encryption. |
| **Viaim** (RecDot, OpenNote, NoteKit) | App Store listing, retailer and press pages only ([App Store](https://apps.apple.com/app/id6468928677), [SoundGuys](https://www.soundguys.com/viaim-makes-ai-earbuds-for-people-who-actually-work-for-a-living-156720/)). No API, SDK, protocol or community work found. Their site (viaim.ai) was not checked. | **No-go.** |
| **TicNote** (Mobvoi) | The launch material lists a TicNote API for AI assistants, Notion/Obsidian export, and "bring your own model" keys ([TechNode](https://technode.global/prnasia/mobvoi-announces-ticnote-watch-the-ai-note-taker-that-captures-your-day-from-your-wrist/)); no API reference, auth documentation or BLE information was found. These are cloud features behind a vendor account. | **No-go** for a BLE driver; the cloud API is outside the product rules. Check ticnote.ai for developer docs if this is revisited. |
| **Sandbar Stream ring** | Notes sync to the Stream app over Bluetooth, iOS first; preorders with shipping planned from about June 2026 ([The Decoder](https://the-decoder.com/sandbar-claims-its-finger-worn-ai-device-is-the-new-mouse-for-voice/), [ChannelNews](https://www.channelnews.com.au/ai-powered-stream-ring-records-voice-notes-on-the-go/)). Those articles pre-date the launch window, and a search on 2026-11-03 found no source confirming that shipments started; the vendor site was not checked. No SDK or public API found. | **No-go on current evidence** (nothing documented found; shipping status unconfirmed). |
| **iFlytek / DingTalk A1** | Product and review pages for the iFlytek P1, P1 Pro and S6 describe Bluetooth plus the vendor app and web platform, with no SDK or developer program ([Notebookcheck P1](https://www.notebookcheck.net/iFLYTEK-AI-Recorder-P1-An-AI-assistant-as-a-wearable-device.1335699.0.html), [iFlytek S6](https://uk.iflytek.com/products/iflytek-ai-recorder-s6)). Nothing at all was found for the DingTalk A1, and iFlytek's and DingTalk's own developer portals (largely Chinese-language) were not searched. | **No-go on current evidence**, with that search gap stated. |
| **Limitless Pendant** (feeds #18) | Two public clients exist and both derive from the closed Limitless Android app. [MAkcanca/pendant-cli](https://github.com/MAkcanca/pendant-cli) is MIT and says it was "built from static analysis of the official Android app and the firmware v1.1.20 image", tested against a real pendant on that firmware. [sdelcore/pendant-cli](https://github.com/sdelcore/pendant-cli) has no formal license and says "For personal/research use only. The protocol was reverse-engineered from proprietary software." (protobuf over two characteristics; its README describes optional audio encryption using "X25519 key exchange with ChaCha20-Poly1305"). Both clients send commands (start/stop recording, clear storage, factory reset, set key), so the device protocol has writes; the Velum profile declares none. | **Pending criterion.** Whether to build on RE-derived work is an owner decision; any driver needs its own write guard designed first (see #18). |

**Unreconciled source conflict (Limitless crypto):** MAkcanca/pendant-cli's file tree describes `crypto.py` as "ECDH-P256 + HKDF-SHA256 + AES-GCM-128", while sdelcore/pendant-cli's README says "X25519 key exchange with ChaCha20-Poly1305". Both claim to derive from the same closed app, and no source found reconciles them (different firmware, a different layer, or one of them is wrong). The roadmap does not pick one; a driver would need independent verification first.

Result of the first two passes: no device was marked go. The third pass below changes that for the Soundcore Work only.

### Second pass (2026-11-03, public pages and search only)

Narrows the gaps the first pass listed; it does not close them. Still no repository, SDK, protocol write-up or developer documentation for any of these, so every decision stays no-go on current evidence. Nothing was captured, decompiled or probed.

- **Viaim (RecDot, OpenNote):** the store pages ([RecDot](https://store.viaim.ai/collections/all/products/viaim-recdot), [OpenNote](https://store.viaim.ai/collections/all/products/viaim-opennote)) list Bluetooth 5.2/5.3 and the codecs SBC, AAC and LHDC. The earbuds record on their own flash storage: a FlashRecord started from the case button or a stem squeeze, then synced to the Viaim app ([FlashRecord and sync](https://intercom.help/hong-kong-future-intelligent-technology-co-ltd/en/articles/16078199-use-flashrecord-and-sync-with-recdot), [recording modes](https://intercom.help/hong-kong-future-intelligent-technology-co-ltd/en/articles/16078188-choose-a-recording-mode)). So a device-side recording and transfer path exists; what is missing is any documentation of it. Vendor-side export is cloud-only through [Zapier](https://intercom.help/hong-kong-future-intelligent-technology-co-ltd/en/articles/16078205) (titles, transcripts and to-dos to other apps), which needs a Viaim account. **No-go** because the transfer path is undocumented, not because none exists. NoteKit is a different product: a USB device for the Viaim desktop app, with its own microphones, that can pair with the earbuds ([setup article](https://intercom.help/hong-kong-future-intelligent-technology-co-ltd/en/articles/12826613-setting-up-notekit-pairing-connection)). A search for an SDK, API or GitHub project found nothing; viaim.ai itself was not read.
- **Sandbar Stream ring:** the [TechCrunch launch article](https://techcrunch.com/2025/11/05/former-meta-employees-launch-a-ring-to-take-voice-notes-and-control-music/) and [The Decoder](https://the-decoder.com/sandbar-claims-its-finger-worn-ai-device-is-the-new-mouse-for-voice/) describe an iOS-first app and preorders with shipping planned for 2026; no source found confirms shipments started. Sandbar's [Stream MCP page](https://www.sandbar.com/technology/stream-mcp) lets AI apps search and export captured notes after signing in; it says nothing about Bluetooth, an SDK or an API, so it is a cloud route behind an account, not a device protocol. The rest of the vendor site was not read.
- **TicNote (Mobvoi):** the [App Store listing](https://apps.apple.com/py/app/ticnote-ai-recorder-agent/id6739864932) and retailer pages describe the consumer product only; the launch-time API mention is the [TechNode](https://technode.global/prnasia/mobvoi-announces-ticnote-watch-the-ai-note-taker-that-captures-your-day-from-your-wrist/) item cited above. No API reference or BLE information was found. ticnote.ai (and ticnote.com / ticnote.cn) was not read, so developer documentation there is still unchecked.
- **iFlytek / DingTalk A1:** DingTalk's [own explainer](https://www.dingtalk-global.com/news/explain/ai-meeting-recording-tool-revolution-26010999) says the A1 has a built-in 6nm AI audio chip doing speech-to-text on the device and that "open APIs" sync requirements to systems such as Salesforce; it mentions no Bluetooth or SDK. Those APIs are DingTalk's general cloud APIs; that they would expose A1 recordings is an inference, not something the page says. DingTalk's [March 2026 release page](https://www.dingtalk-global.com/news/activity/dingtalk-new-ai-hardware-release-260319) announces the A1 Pro and the Cleer H1 co-branded headphones (on sale April 30) and the Wukong work platform; it does not name an SDK. The Chinese-language developer portals (open.dingtalk.com, iFlytek's open platform) were not read.
- **Limitless Pendant (feeds #18):** Meta announced the acquisition of Limitless on 2025-12-05 and new pendant sales ended; existing owners were promised support for at least a year ([TechCrunch](https://www.techcrunch.com/2025/12/05/meta-acquires-ai-device-startup-limitless/)). A Neowin headline snippet and a secondary site report that service ended in some regions around 2025-12-19; the Neowin page could not be opened (HTTP 403), so treat that date as unconfirmed. [MAkcanca/pendant-cli](https://github.com/MAkcanca/pendant-cli) (MIT, 9 commits) lists commands including StartRecording, StopRecording, DownloadFlashPages, DeleteFlashPage, ClearPendantStorage, FactoryResetPendant, ResetDevice and UnpairBluetooth, and was tested against firmware v1.1.20 only.

**Still open after the second pass** (the third pass below closes the ones it could): viaim.ai, Sandbar's vendor site beyond the MCP page, ticnote.ai/.com/.cn developer docs, the DingTalk and iFlytek developer portals, and the Limitless end-of-service date from the vendor.

### Third pass (2026-11-04, public sources only)

Searched GitHub, search engines and vendor pages for community reverse engineering, open SDKs and export paths. Nothing was
captured, decompiled or probed by Velum, no vendor was contacted, and nothing was bought. Repository facts (license, dates)
are from the GitHub API on 2026-11-04. A star count or an author's "works on my device" is not independent verification; where a claim
rests on one author, it says so.

**Go criterion used, stated plainly:** #18 already accepts implementing from community protocol work, verified with fixtures
and a simulated peripheral and shipped labelled community-tested. This pass applies that same rule, so "go" means: a
documented protocol, at least two independent implementations, at least one under an open license, and nothing that needs a vendor
account. The owner can still tighten the rule; the decisions below follow from it.

| Device | New evidence | Decision |
| --- | --- | --- |
| **Soundcore Work** (Anker D3200; also sold as the Feishu "recording bean") | Three independent community codebases. [tacshi/Soundcore](https://github.com/tacshi/Soundcore) (Flutter app; `PROTOCOL.md`, 657 lines, "Reverse-engineered from the Feishu Android APK ... embedded Anker SDK"; created 2026-07-12, pushed 2026-10-12; the GitHub API reports no license and the file list has no LICENSE, so treat its text and code as not reusable). [Shawn-TKD/recording-bean-web](https://github.com/Shawn-TKD/recording-bean-web) (MIT, a browser client, created 2026-08-15): BLE direct, "no account", ECDH / HKDF / AES decryption of recordings to Ogg/Opus, start/pause/resume recording, download, delete; its README leaves out unbind, factory reset, OTA and find-device on purpose. [knorthfield/soundcore-pull](https://github.com/knorthfield/soundcore-pull) (MIT, macOS Swift, created 2026-09-11, pushed 2026-10-21) is a Swift port of the recording-bean-web logic that lists and pulls recordings over BLE with "no phone, no account, no cloud service". The protocol notes describe a BLE service plus Wi-Fi SoftAP bulk export, an ECDH P-256 key exchange (type `0x2E`) and encrypted per-file keys. They list destructive commands: delete file (`1A 10`), unbind (`0x87`), factory wipe (type `0x01`, id `0xB8`) and OTA. The same notes say Anker's SDK also has a license/auth step against Anker's cloud and that "local BLE control may work with reduced/offline license depending on firmware policy" (their wording, not tested here). | **Go as community-RE.** The tacshi notes come from decompiling a vendor app, which is the "RE-derived" question the owner may still want to rule on. Conditions: implement from the MIT clients and the facts in the notes, never copy unlicensed code; a deny-by-default write guard (the #19 pattern) that forbids delete, unbind, factory wipe and OTA; community-tested label; fixtures and a fake peripheral because the owner has no device. Driver task opened: #432. |
| **iFlytek / DingTalk A1** | [Shawn-TKD/dingtalk-a1-pc-tools](https://github.com/Shawn-TKD/dingtalk-a1-pc-tools) (MIT, created 2026-08-16, pushed 2026-09-15, 14 stars): BLE service `0000fe3c-...`, a challenge-response (`getRandom` 0x0008, `connectDevice` 0x0133) whose token is AES-128-CBC keyed by a per-device `deviceSecret`. Its README says the secret is not derivable from serial, DID or MAC; it is taken from the owner's DingTalk account session (the DingTalk app's `PreferenceUtils.xml`, extracted on a rooted emulator), and one device's secret fails on another (`code: 501`). Read-only by design (no factory reset, delete, unbind or OTA). No source for iFlytek P1/P1 Pro/S6 beyond the earlier product pages; a search summary of an iFlytek recorder launch describes offline transcription and "secure offline USB data transfer" and mentions no Bluetooth ([MarketScreener](https://ca.marketscreener.com/quote/stock/IFLYTEK-CO-LTD-6500190/news/IFLYTEK-Launches-Smart-Recorder-46578171/); I did not open the page). | **No-go.** Pairing needs a credential taken out of the owner's vendor account, which conflicts with Velum's "no account, no vendor login" rule, and there is a single implementation. Revisit if a way to pair without the account secret is documented. |
| **Bee (Amazon)** | [cewinharhar/bee_local](https://github.com/cewinharhar/bee_local): created and last pushed 2026-02-05, 3 commits, 0 stars. README says "MIT"; the GitHub API reports no license and there is no license file. Its `docs/BEE_PROTOCOL_SPEC.md` (583 lines) says "Source: Decompiled Android APK v1.0.1 (`com.bee.android`)" and "Status: Complete reverse engineering specification"; it lists 16-bit little-endian command codes, a control and an audio characteristic, OPUS or AAC audio, and a second set of UUIDs for "newer firmware" described as "Discovered via Live Scanning". The spec has no encryption section and does not say it was checked against a device; the earlier note that newer firmware encrypts streams (community reports) is not confirmed or refuted by it. The README says the official Bee app "was delisted from app stores". No second implementation found. | **No-go for now, watch.** One source, not independently verified, unclear license, firmware variations unreconciled. Revisit when a second implementation or an independent capture appears. |
| **Viaim** (RecDot, OpenNote, NoteKit) | A search result summary describes a separate "viaim AI Open" Python SDK on PyPI (`viaim-ai-open` 0.1.0, Apache-2.0) for server-side speech recognition with an app key and secret; the PyPI page itself failed to load for me, so this is unverified, and it is a cloud speech API, not a recorder protocol. Viaim's help pages say recording is started from the Viaim app. No device protocol, repository or community work found. | **No-go.** |
| **TicNote** (Mobvoi) | TicNote's own [Codex user guide](https://ticnote.com/skill/codex-user-guide) describes an API key from the "TicNote Key" profile menu that lets an AI agent read recording transcripts; "Available on Professional and Business plans". It names transcripts only, lists no endpoints, and says nothing about Bluetooth or device access. A Notion export connector exists. No public API reference or developer sign-up page found. | **No-go** for a BLE driver (cloud API behind a paid account, outside the product rules). |
| **Sandbar Stream ring** | The [Stream MCP page](https://www.sandbar.com/technology/stream-mcp) says "Stream is an MCP server" with a URL from the Stream app settings; it mentions no API, SDK, Bluetooth protocol, developer access or shipping status (only a Preorder link). No GitHub repository found for it. | **No-go.** |
| **Limitless Pendant** (answers #18) | Vendor statement, [limitless.ai](https://www.limitless.ai/): "Limitless has been acquired by Meta"; "We will continue to support existing Pendant customers throughout 2026"; the service ended on 2025-12-05 in Brazil, China, the EU, Israel, South Korea, Turkey and the UK, with data export until 2025-12-19; [TechCrunch](https://techcrunch.com/2025/12/05/meta-acquires-ai-device-startup-limitless/) says support continues "for a year" and hardware sales stopped. That settles the end-of-service question: the vendor commits to 2026 only, with no later date. Newer community tools: [girlflipwebsite-spec/limitless-pendant](https://github.com/girlflipwebsite-spec/limitless-pendant) (no license, created 2026-09-13; read-only by design, "No Limitless cloud account needed"; reports firmware 1.1.20 and warns that a pendant with audio encryption turned on cannot be read without a decryption decision) and [justintormey/limitless-pendant-extractor](https://github.com/justintormey/limitless-pendant-extractor) (MIT, USB serial and a BLE iOS app). The crypto conflict between the MAkcanca and sdelcore clients is still unreconciled, and the new evidence adds that encryption is per pendant. | **No-go for a driver now (unchanged).** The #18 answer: a public protocol exists in pieces but no source is complete, license-clean and verified together, the crypto conflict stands, encryption varies per device, and the vendor support window is 2026. Close #18 with a link here, or keep it only as a read-only importer later, after a community user's capture reconciles the crypto. |

**Per-device go/no-go after this pass:** Soundcore Work go; Bee no-go (watch); Viaim no-go; TicNote no-go; Sandbar no-go;
iFlytek/DingTalk A1 no-go; Limitless no-go (#18 answered).

**Could not verify:** whether any of these projects work on a real unit beyond their authors' statements; the Anker SDK
license/auth step's effect on a fresh device; the Viaim SDK details (page failed to load); vendor developer portals in
Chinese (DingTalk open platform, iFlytek) were only reached through search, not read; Sandbar shipment status.

## Driver architecture

```
RecorderDriverRegistry
  ├─ PlaudRecorderDriver     (file sync + SoftAP)
  ├─ OmiRecorderDriver       (live Opus/PCM stream; registered, not usable in the app yet)
  └─ PocketRecorderDriver    (ASCII + MP3 — protocol first; registered, not usable in the app yet)
```

Each driver exposes: scan filter, identify, connect/auth, list/download (if any), live stream (if any), battery, capabilities. UI/BLE transport stay generic; Plaud-specific GATT writes remain behind the Plaud session adapter until multi-GATT transport lands.

### Guarded GATT writes (#19)

All GATT writes go through `dev.velum.ble.gatt.GuardedLink`: it looks up the `WriteGuard` declared for the target
characteristic in the `GattProfile` (Plaud: 2BB1 -> `PlaudWriteGuard`, forbidden opcodes 5/30/104/128/0xFE20/143 and
settings 4/26), runs it on the **plaintext**, then optionally seals. `GattLink.writeRaw` is public so test fakes in
`:ble-testing` can implement it, but it only accepts `GattWrite` values whose constructors are `internal` to `:ble`,
so only `GuardedLink` can produce one. Drivers and app code hold `BleTransport` / `GuardedLink` only.
`GattArchitectureTest` fails if any module other than `ble` / `ble-testing` mentions `writeRaw`, `GattWrite`,
`PlainWrite(` or `SealedWrite(`, or `sendEncryptedFrame` (the Wi-Fi socket write, called only by `WifiFastTransferSession`,
which runs the same guard first). The Omi, Pocket and Limitless profiles exist (`GattProfiles`) and declare no
writable characteristic, so `GuardedLink` refuses every write under them; a driver that needs to write would first
have to add its own guard for a named characteristic. No driver is wired to the Limitless profile (#18).

---

## Hardware verification

| Driver | Unit / fixture tests | Real hardware |
| --- | --- | --- |
| Plaud | Yes (existing) | Owner devices (ongoing) |
| Omi | Yes (packet fixtures) | **Unverified** |
| Pocket | Framing tests | **Unverified** |
| Limitless codec | Vendored; no driver session yet | **Unverified** |
| Bee / Soundcore / … | — | — |
