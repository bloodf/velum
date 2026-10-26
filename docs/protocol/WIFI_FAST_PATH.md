# Wi-Fi fast transfer (SoftAP + TCP :8081)

**Status:** JVM fake SoftAP E2E green (preamble reject, happy path, BLE fallback). Confirm on real Note Pro hardware.

## Plaud APK 3.30.0 (device parity we keep)

| Piece | Behavior |
|-------|----------|
| SoftAP | Phone joins device SoftAP via `WifiNetworkSpecifier` (WPA2) |
| Port | Phone listens on **:8081** |
| Crypto | Same BLE session **key / nonce / aad**; ChaCha20-Poly1305, or **AES-GCM** when cmd 138 bit `0x08` |
| Nonce | **Fixed for the whole session** — Plaud protocol parity debt, required for Note Pro SoftAP interoperability. Do not rotate per frame. |
| Seq | Each frame plaintext is `seq_u32_le ‖ packet` |
| BLE | Active BLE session required; Wi-Fi is an acceleration path |

Plaud's app uses WebSocket on `:8081`. Velum uses **length-prefixed TCP** on the same port with the **same session cipher** so SoftAP crypto stays interoperable when hardware arrives.

## Velum SoftAP phone listener

- **Per-socket SoftAP bind** via `Network.socketFactory` / `Network.bindSocket` — never `bindProcessToNetwork`.
- **Bind address** is the SoftAP interface IPv4 when known (not `0.0.0.0`). JVM/fake tests bind `127.0.0.1` explicitly.
- **Token preamble** (magic `VLWF` + bind-token or first 16 bytes of session key) before any encrypted frame — Velum-side phone listener auth.
- **Frame hardening** — u32 BE length prefix; reject ≤0, truncated reads, and bodies over 8 MiB.
- **BLE fallback** (`WifiTransferCoordinator`) when bit `0x08` is absent, or on SoftAP / preamble / IO / timeout / decrypt failure.

## Code map

- `WifiFastTransferSession` / `WifiTransferServer` / `WifiTransferCoordinator` / `WifiTransferFraming` / `WifiTransferPreamble`
- Android: `AndroidWifiSoftApJoiner`, `AndroidWifiTransferServer`, `SoftApSocketBinder`
- JVM fake: `FakeWifiNetwork`, `FakeWifiSoftApJoiner`, `FakeWifiClient`, `JvmWifiTransferServer`
- Sync entry: `PlaudDeviceClient.downloadSessionPreferWifi`
- Tests: `WifiFastPathE2ETest`

No Plaud servers are contacted.
