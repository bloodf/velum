# Plaud BLE / Wi-Fi protocol research for Velum (pass 2, 2026-10-26)

> Facts-only adaptation for Velum. Do **not** copy code from openplaudit (BSL 1.1) or opendict (no license). Prefer MIT/Apache sources below.

## Resumo (PT)
- **Firmware 1.5 do Note Pro:** evidência forte e indireta de **protocolo 20 (RSA + ChaCha20-Poly1305)**. Confiança **média-alta**; confirmar com anúncio BLE (bytes de `portVersion`).
- **Apagar:** pedido **30**, resposta **31** (não testado no Note Pro).
- **Bateria:** característica padrão **0x180F/0x2A19** (leitura + notify); também cmd 9 → `charging u8, level u8`.
- **Opcodes variam por modelo** — Velum usa **tabela por modelo** + `portVersion` do anúncio.

## Owner device (nRF Connect)
Note Pro firmware 1.5, name `PLAUD NOTE PRO`. Observed GATT services include:
`0x1801`, `0x1800`, `000002fd-3c17-d293-8e48-14fe2e4da212`, Battery `0x180F`, `0x1910`, Device Information `0x180A`, Accessory Information `87290102-3c51-43b1-a1a9-11b9dc38478b`, Find My `0xFD44`, Tx Power `0x1804`.

Use **`0x2A19`** for battery and **`0x180A`** for firmware/model info.

## Framing and GATT (high confidence)
| Claim | Confidence | Source |
|---|---|---|
| Service 0x1910, notify 0x2BB0, write 0x2BB1 | High | [PA], [Kurikara], [Atticus] |
| Battery 0x180F / 0x2A19 read + notify | High | [Atticus], [SDKdoc] |
| Frame `[type u8][cmd u16 LE][payload]` | High | [PA], [Kurikara] |
| Data `[0x02][session?][offset][len][data]`, end `0xFFFFFFFF` | High | [PA] |
| Manufacturer 0x005D; portVersion LE16 @ byte 18 (881/888) | Medium-high | [naeem], [OpenPlod#3] |
| Project codes: 860 One, 880 NotePin, 881 Note Pro, 882 NotePin S, 888 Note | Medium | [SDKdoc], [OpenPlod#3] |
| port &lt; 20 plaintext; ≥ 20 RSA + ChaCha20-Poly1305 | High | [PA], [SDKdoc] |

## Opcode highlights
| Cmd | Name | Notes | Status |
|---|---|---|---|
| 0xFE10–0xFE12 | Proto 20 unlock | SN sig / pubkey / session | ✅P |
| 0xFE30–0xFE34 | Proto 30 ECC | Plaud One | 🔶 |
| 1 | Handshake | status, port, channels, ogg | ✅P |
| 3 | Recording state | | ✅P |
| 4 | Set clock (**Pro**) | Note uses 38 | ✅P / ⚠️ |
| 6 | Storage | free/total/remaining | ✅N |
| 8 | Common settings | read/write by id | ✅P |
| 9 | Battery | charging, level | ✅P |
| 20–23 | Record control | start/pause/resume/stop | ✅P |
| 26 | List sessions | | ✅P |
| 28/29 | Sync start/stop | | ✅P |
| **30 → 31** | **Delete file** | untested on Pro | 🔶 |
| 107/108 | BLE name (Pro) | | ✅P |

**Never send:** 5 (depair), 104 (clear all), 0xFE20, OTA 143, settings writes that enable cloud auto-sync / auto-delete.

## Model / firmware matrix
| Model | Firmware | Port | Notes |
|---|---|---|---|
| Note Pro 881 | 1.0.0 | 20 | full pull tested |
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
| naeem / opendict | — | **No license** — facts only, no copy |
| openplaudit | — | **BSL 1.1** — ideas only, no code |

## Safe read-only test plan (owner device)
1. nRF Connect: read 0x005D → project @1, port @18.
2. Read/notify 0x2A19 battery.
3. If port 20: unlock + handshake; log fields.
4. Read-only cmds 3, 6, 9, 26, cmd 8 reads.
5. Short then long download with resume.
6. Delete (30) only on throwaway file after approval.
7. Wi-Fi only as phase 2 after approval.
