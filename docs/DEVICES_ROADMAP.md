# Multi-device recorder roadmap

Research date: 2026-10-27. Goal: support other famous Bluetooth AI note-takers / wearables that have **open-source or well-documented community protocols**, via a pluggable [`RecorderDriver`](../recorder/src/commonMain/kotlin/dev/velum/recorder/RecorderDriver.kt).

**Reuse policy (owner):** copy open-source code aggressively into Velum modules when it helps (MIT/Apache/BSD/ISC/Unlicense/MPL **and** GPL/AGPL if useful). Keep each file’s original copyright/license header. Record every copy in [THIRD_PARTY.md](../THIRD_PARTY.md). Owner accepts sorting licenses later.

---

## Ranked feasibility

| Rank | Device / project | OSS / docs | License | Local transfer w/o vendor cloud | Codec | Auth | Feasibility for Velum | Status in repo |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **Plaud** (Note / NotePin / Note Pro / One) | Community RE + Velum | Research notes MIT; firmware proprietary | **Yes** (BLE + SoftAP WS) | Ogg Opus / Opus / ADPCM | FE10 snSignature + session AES | **Shipped** | `PlaudRecorderDriver` |
| 2 | **Omi / Friend** ([BasedHardware/omi](https://github.com/BasedHardware/omi)) | Official protocol docs + firmware | **MIT** | **Yes** (live BLE stream) | Opus 16 kHz (default), PCM, μ-law | Optional DIS; no cloud unlock | **High — shipped driver** | `OmiRecorderDriver` + packet fixtures |
| 3 | **Pocket** + **OpenPocket** DIY ([pocket-libre](https://github.com/shahcolate/pocket-libre), [open-pocket](https://github.com/TanskiSzymon/open-pocket)) | PROTOCOL.md + MIT client | **MIT** (client/scripts); HW CERN-OHL-P | **Yes** (BLE MP3 + Wi‑Fi) | MP3 16 kHz mono ~32 kbps | 16-char session key (`APP&SK&`) | **High — protocol + driver** | `PocketRecorderDriver` + framing tests |
| 4 | **Limitless Pendant** (omi `LimitlessProtocol.kt` + [pendant-cli](https://github.com/MAkcanca/pendant-cli)) | MIT codec in-tree | **MIT** | **Yes** (BLE flash drain) | Opus in flash pages | ECDH + AES-GCM | High | `LimitlessProtocol` vendored; driver later |
| 5 | **OpenGlass / OmiGlass** ([OpenGlass](https://github.com/BasedHardware/OpenGlass) → omi) | MIT firmware; audio PR uses Omi-like BLE | **MIT** | Yes (Opus BLE) | Opus | Same family as Omi | Use **Omi driver** / alias | Via Omi UUIDs |
| 6 | **DIY XIAO / ESP32 wearing Friend firmware** | Omi open firmware | MIT | Yes | Opus/PCM | Same as Omi | **Same as Omi** | Matches Omi scan filter |
| 7 | **whistle-esp32** ([andrisgauracs/whistle-esp32](https://github.com/andrisgauracs/whistle-esp32)) | On-device Whistle ASR demo | **Apache-2.0** | N/A — **no phone BLE audio transfer** | Local ASR only | N/A | **Not a RecorderDriver** | Documented only |
| 8 | **Bee (Amazon)** | bee-cli / API proxy; BLE opaque | Proprietary / closed BLE | App/API path, not open BLE | Encrypted streams (community reports) | Pairing + Amazon stack | Low until open HAL | Watch |
| 9 | **Soundcore Work** | None found | Proprietary | Vendor app | Unknown | Vendor | Low | — |
| 10 | **Viaim / Mobvoi TicNote / Sandbar / iFlytek·Dingtalk A1** | Marketing only | Proprietary | Vendor cloud typical | Unknown | Vendor | Low | Brazil/Asia market watch |
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
- pocket-libre MIT — credit PROTOCOL.md; reimplement commands in Kotlin.

### Limitless Pendant (later)

- Protobuf over dual characteristics; flash-page Opus; MIT community client exists.
- Prefer clean-room from pendant-cli docs + fixtures; heavy surface.

### whistle-esp32 (not a recorder target)

- Runs Whistle ASR **on the MCU**; button → local transcript / LED.
- No documented BLE GATT for dumping meeting audio to a phone.
- Useful as inspiration for on-device ASR, not as `RecorderDriver`.

### Bee / Soundcore / Viaim / TicNote / Sandbar / iFlytek A1

- No permissive, complete public BLE audio protocol suitable for Apache-2.0 reimplementation found in this pass.
- Bee post-Amazon: developer CLI/API; BLE reportedly encrypted on newer firmware — not driver-ready.

---

## Driver architecture

```
RecorderDriverRegistry
  ├─ PlaudRecorderDriver     (file sync + SoftAP)
  ├─ OmiRecorderDriver       (live Opus/PCM stream)
  └─ PocketRecorderDriver    (ASCII + MP3 — protocol first)
```

Each driver exposes: scan filter, identify, connect/auth, list/download (if any), live stream (if any), battery, capabilities. UI/BLE transport stay generic; Plaud-specific GATT writes remain behind the Plaud session adapter until multi-GATT transport lands.

---

## Hardware verification

| Driver | Unit / fixture tests | Real hardware |
| --- | --- | --- |
| Plaud | Yes (existing) | Owner devices (ongoing) |
| Omi | Yes (packet fixtures) | **Unverified** |
| Pocket | Framing tests | **Unverified** |
| Limitless codec | Vendored; no driver session yet | **Unverified** |
| Bee / Soundcore / … | — | — |
