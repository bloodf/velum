# Plaud BLE / Wi-Fi protocol research for Velum (pass 3, APK 3.30.0)

> Facts-only adaptation for Velum. Do **not** copy code from openplaudit (BSL 1.1) or opendict (no license). Prefer MIT/Apache sources below.
> Cross-checked against static analysis of Plaud Android **3.30.0** (`docs/APK_ANALYSIS.md`). Nothing in the APK contradicted prior research.

## Resumo (PT)
- **Firmware 1.5 do Note Pro:** evidência forte e indireta de **protocolo 20 (RSA + ChaCha20-Poly1305)**. Confiança **média-alta**; confirmar com anúncio BLE (bytes de `portVersion`).
- **Apagar:** pedido **30**, resposta **31** (não testado no Note Pro).
- **Bateria:** característica padrão **0x180F/0x2A19** (leitura + notify); também cmd 9 → `charging u8, level u8`.
- **Opcodes variam por modelo** — Velum usa **tabela por modelo** + `portVersion` do anúncio.
- **APK 3.30.0:** SoftAP + WS phone `:8081`; cmd **138** bit `0x08` = AES-GCM no Wi-Fi; FE10 **em toda conexão** (prot ≥ 20); writes também criptografados com seq do app; projetos **881/883** sem OTA push.

## Owner device (nRF Connect)
Note Pro firmware 1.5, name `PLAUD NOTE PRO`. Observed GATT services include:
`0x1801`, `0x1800`, `000002fd-3c17-d293-8e48-14fe2e4da212`, Battery `0x180F`, `0x1910`, Device Information `0x180A`, Accessory Information `87290102-3c51-43b1-a1a9-11b9dc38478b`, Find My `0xFD44`, Tx Power `0x1804`.

Use **`0x2A19`** for battery and **`0x180A`** for firmware/model info.

## Framing and GATT (high confidence)
| Claim | Confidence | Source |
|---|---|---|
| Service 0x1910, notify 0x2BB0, write 0x2BB1 | High | [PA], [Kurikara], [Atticus], APK `ui.x4` ✅ |
| Battery 0x180F / 0x2A19 read + notify | High | [Atticus], [SDKdoc] |
| Frame `[type u8][cmd u16 LE][payload]` | High | [PA], [Kurikara] |
| Data `[0x02][session?][offset][len][data]`, end `0xFFFFFFFF` | High | [PA] |
| Manufacturer 0x005D; portVersion LE16 @ byte 18 (881/888) | Medium-high | [naeem], [OpenPlod#3] |
| Project codes: 860 One, 880 NotePin, **881/883 Note Pro family**, 882 NotePin S, 888 Note | High | [SDKdoc], [OpenPlod#3], APK OTA ✅ |
| port &lt; 20 plaintext; ≥ 20 RSA + ChaCha20-Poly1305 | High | [PA], [SDKdoc], APK ✅ |
| Link: **fixed** key/nonce/aad for the whole session; plaintext = `seq u32 LE ‖ packet`; app increments its own write seq | High | APK `ui.y4` + `wi.m` ✅ |
| FE10 `snSignature` every connect when prot ≥ 20; empty → handshake fail | High | APK ✅ |
| FE12 PEM = **user** E2EE RSA public key (not device key) | High | APK ✅ |

## Opcode highlights
| Cmd | Name | Notes | Status |
|---|---|---|---|
| 0xFE10–0xFE12 | Proto 20 unlock | SN sig every connect / pubkey / session | ✅P + APK |
| 0xFE30–0xFE34 | Proto 30 ECC | Plaud One | 🔶 |
| 1 | Handshake | bind token padded `'0'` to 16 (or 32 if version ≥ 9) | ✅P + APK |
| 3 | Recording state | | ✅P |
| 4 | Set clock (**Pro**) | Note uses 38 | ✅P / ⚠️ |
| 6 | Storage | free/total/remaining | ✅N |
| 8 | Common settings | read/write by id; **never write** ids 4 / 26 | ✅P + APK |
| 9 | Battery | charging, level | ✅P |
| 20–23 | Record control | start/pause/resume/stop | ✅P |
| 26 | List sessions | | ✅P |
| 28/29 | Sync start/stop | | ✅P |
| **30 → 31** | **Delete file** | untested on Pro | 🔶 |
| 107/108 | BLE name (Pro) | | ✅P |
| **120–125** | Sync-in-idle Wi-Fi | get/set SSID+password / list / test / result / delete | APK 🆕 |
| 116 → 117 | Wi-Fi disconnect | | APK |
| **138** | FeatureRequest | after skip 3 bytes, **bit 0x08 = AES-GCM Wi-Fi** | APK 🆕 |
| 112/114 → 113, 143 → 143/144 | OTA | **disabled for project 881/883** | APK |

**Never send (ForbiddenOpcodes):** 5 (depair), **128 (resetFindmy)**, 104 (clear all), 0xFE20, OTA **143**.

**Never WRITE settings (ForbiddenSettings):** **4** (AUTO_DELETE), **26** (AUTO_SYNC).

**Unused in APK command map (do not send):** 5, 13, 38, 50, 51, 109, 141, 142, 145, 151 — mirrored as `UnusedCommands`.

## Wi-Fi fast transfer (APK confirmed)
- Phone runs a **WebSocket server on `:8081`**; device SoftAP joins via `WifiNetworkSpecifier` (WPA2).
- Every WS frame is encrypted with the **same BLE session key/nonce/aad** (ChaCha20-Poly1305, or **AES-GCM** if cmd 138 bit `0x08`), with its own seq.
- Active BLE session required; no Plaud cloud involved for the pipe itself.
- Velum: local SoftAP + WS interfaces only — never contact `api.plaud.ai`.

## Model / firmware matrix
| Model | Firmware | Port | Notes |
|---|---|---|---|
| Note Pro **881 / 883** | 1.0.0 | 20 | full pull tested (881); **no OTA push** for 881/883 |
| Note Pro 881 | 1.4 / 1.5 | 20 inferred | pairs with third-party apps |
| Note Pro 881 | 1.7.0 | 20 measured | SDK bind/list/sync |
| Note 888 | NB-100 | 10 plaintext | |
| Note 888 | recent | 20 | raw Opus segments |
| NotePin 880 | ? | 20 | |
| Plaud One 860 | ? | 30 ECC | |

## Sources and licenses
| Tag | URL | License |
|---|---|---|
| PA / DO | https://github.com/roopen219/plaude-archiver | MIT |
| OpenPlod | https://github.com/RemiPelloux/OpenPlod | MIT |
| Kurikara | https://github.com/Kurikara-dev/plaud-note-nb100-re | MIT |
| Atticus | https://github.com/beekeeper-lab/atticus | Apache-2.0 |
| Martin | https://github.com/Martin-Hausleitner/recorder-device-sdks | MIT |
| Omi | https://github.com/BasedHardware/omi | MIT |
| SDKdoc | https://docs.plaud.ai/… ; Plaud-AI/plaud-sdk-public | Vendor docs — do not bundle |
| APK 3.30.0 | `docs/APK_ANALYSIS.md` | Static analysis notes only |
| naeem / opendict | — | **No license** — facts only, no copy |
| openplaudit | — | **BSL 1.1** — ideas only, no code |

## Safe read-only test plan (owner device)
1. nRF Connect: read 0x005D → project @1, port @18.
2. Read/notify 0x2A19 battery.
3. If port 20: unlock + handshake; log fields. Confirm FE10 required every reconnect.
4. Read-only cmds 3, 6, 9, 26, cmd 8 reads. FeatureRequest 138 bit 0x08.
5. Short then long download with resume.
6. Delete (30) only on throwaway file after approval.
7. Wi-Fi SoftAP + local WS `:8081` only after approval — no cloud.
