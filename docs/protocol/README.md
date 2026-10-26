# Plaud BLE protocol notes (adapted)

These notes are an **adaptation** for Velum developers. They are **not** a dump of any proprietary SDK.

Deep research (opcodes, Wi-Fi, model matrix, safe test plan):
[PROTOCOL_RESEARCH.md](PROTOCOL_RESEARCH.md).

## Sources (please respect their licenses)

| Project | License | What we used |
|---------|---------|----------------|
| [plaude-archiver](https://github.com/roopen219/plaude-archiver) | MIT | GATT layout, protocol 20 unlock, session list/download, `PLAUD.AI` file layout, byte fixtures from a real Note Pro (fw 1.0.0) |
| [OpenPlod](https://github.com/OpenPlod/OpenPlod) | MIT (see their `LICENSING.md`) | Android BLE transport patterns (MTU 247), device matrix |

Credit the authors if you copy substantial text; prefer linking upstream. Velum’s Kotlin codec in `:plaud-protocol` was written from these descriptions and **does not** include Plaud cloud Fernet keys or APK assets.

## High-level summary

- **Service** `00001910-…`, notify `2BB0`, write `2BB1`
- **Framing:** little-endian; app→device `[0x01][cmd u16][params]`; device→app type byte `1|2|4|5`
- **Protocol 20 unlock:** chunked `0xFE10` serial signature → optional `0xFE11`/`0xFE12` RSA public key → RSA-wrapped ChaCha20-Poly1305 session
- **Commands:** `26` list sessions, `28`/`29` download, type `2` audio packets
- **Files:** `PLAUD.AI` 512-byte header; body ChaCha20; result Ogg Opus 16 kHz mono (Note Pro)

Known gaps (CRC algorithm confirmation, delete semantics, Wi-Fi transfer, protocol 30 ECDH, long-download resume) are tracked in the roadmap and upstream issues.

## Fixtures used in unit tests

Hex dumps exercised in `:plaud-protocol` tests (from community captures / docs):

- Handshake: `010100001400051e0101000156000701`
- Empty session list: `011a00b19cdb6a00000000`
- Battery-like cmd 9: `010900005d`
- Sync stop CRC field: `011d00f49ddb6affff`

## What Velum will not ship

- Plaud account tokens or refresh tokens
- Embedded Fernet / cloud response keys from official apps
- User RSA private keys or serial signatures (user import only)
