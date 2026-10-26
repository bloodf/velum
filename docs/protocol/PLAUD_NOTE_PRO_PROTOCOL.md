# Plaud Note Pro: BLE pull overview (adapted)

> Adapted for Velum from community documentation in
> [plaude-archiver](https://github.com/roopen219/plaude-archiver) (MIT)
> and cross-checked against notes in OpenPlod (MIT).
> This file omits cloud Fernet secrets and any copyrighted app assets.

## GATT

| UUID | Use |
|------|-----|
| `00001910-0000-1000-8000-00805f9b34fb` | Plaud service |
| `00002bb0-…` | Notify (device → app) |
| `00002bb1-…` | Write (app → device) |
| `0000180f` / `00002a19` | Standard battery |

Manufacturer data company id `0x005D`: model in bytes 1–2 (`0x0371` = 881 Note Pro), serial bytes 9–16, protocol version bytes 18–19.

## Framing

All integers little-endian.

- App → device: `[0x01][cmd u16][params]` (trailing zeros trimmed).
- Device → app, byte 0 = type: `1` command response, `2` recording data, `4` speed test, `5` log.

## Protocol 20 unlock (Note Pro fw 1.0.0 verified in community)

Requires user-held: serial signature (256 bytes), RSA-2048 key pair, bind token (`user_id` padded with `0`).

1. Send signature as `0xFE10` chunks `[opcode][total][index][≤100 bytes]` (no `0x01` prefix).
2. If device replies `0xFE11`, send public key PEM via `0xFE12` chunks.
3. Device returns session secret via `0xFE12`; RSA-decrypt → `key[32]|nonce[12]|aad[12]|test` (test opens to `PLAUD.AI`).
4. Subsequent packets: ChaCha20-Poly1305 over `seq u32 || packet`; app send counter starts at 2.

## Handshake (cmd 1)

`01 01 00 | 02 00 | P | token`. Response includes status, protocol version, timezone, channels, Wi-Fi flag, Ogg flag.

## Recordings

| cmd | Role |
|-----|------|
| 26 | List sessions (`session_id` = Unix start, size, attrs) |
| 28 | Sync start |
| type 2 | Data `[offset u32][len u8][bytes…]`; `offset=0xFFFFFFFF` end |
| 29 | Sync stop + CRC field |
| 30 → 31 | Delete (community: untested on HW) |

## On-device file

Magic `PLAUD.AI`, header 512 bytes, version 1 = RSA-wrapped content key, body = ChaCha20 (no Poly1305). Decrypt → Ogg Opus 16 kHz mono ~32 kbps on Note Pro.

## Gaps

Protocol 30 ECDH, Wi-Fi fast transfer, long download resume, exact CRC match, remote record start/stop — not implemented in Velum yet.
