# Wi-Fi fast transfer (Plaud SoftAP + WebSocket :8081)

**Status:** Implemented and on. JVM fake SoftAP E2E green (preamble, encrypted frame, BLE fallback). Confirm on real Note Pro hardware.

## Summary

| Piece | Behavior |
|-------|----------|
| SoftAP | Phone joins device SoftAP via `WifiNetworkSpecifier` (WPA2) |
| Transport | Phone listens WebSocket **`:8081`** (`org.java_websocket`); device connects as client |
| Crypto | BLE session **key / aad**; ChaCha20-Poly1305, or **AES-GCM** when cmd 138 bit `0x08` |
| Nonce | **Unique 96-bit per SoftAP frame** from a CSPRNG (review **S13 resolved**). Wire = `nonce[12] ‖ ciphertext‖tag`. Plaintext = `seq_u32_le ‖ packet`. BLE link packets still use Plaud's fixed session nonce; SoftAP never reuses a (key, nonce) pair. |

## Hardening (beyond Plaud APK)

- **Socket-level SoftAP bind** via SoftAP host bind / `Network.bindSocket` — never `bindProcessToNetwork`.
- **Preamble** first WS binary message (`VELUM-WIFI-v1:<bindToken>`) so a random SoftAP client is rejected.
- **BLE fallback** (`WifiTransferCoordinator` / `PlaudDeviceClient.downloadSessionPreferWifi`) on timeout, SoftAP failure, preamble mismatch, or cipher errors.

## Hardware note

Real Note Pro SoftAP that still expects the Plaud fixed-nonce SoftAP layout will fail open and fall back to BLE until owner hardware confirms the unique-nonce wire format (or a negotiated upgrade). JVM fake SoftAP speaks the Velum unique-nonce layout.
