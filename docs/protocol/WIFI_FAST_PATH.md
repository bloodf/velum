# Wi-Fi fast transfer (Plaud SoftAP + WebSocket :8081)

**Status:** Implemented and on. JVM fake SoftAP E2E green (preamble, encrypted frame, BLE fallback). Confirm on real Note Pro hardware.

## Plaud APK 3.30.0 parity

| Piece | Behavior |
|-------|----------|
| SoftAP | Phone joins device SoftAP via `WifiNetworkSpecifier` (WPA2) |
| Transport | Phone runs **WebSocket server on `:8081`** (`org.java_websocket`, same as Plaud) |
| Crypto | Same BLE session **key / nonce / aad**; ChaCha20-Poly1305, or **AES-GCM** when cmd 138 bit `0x08` |
| Nonce | **Fixed for the whole session** — **required** for Note Pro; do not rotate. **Accepted risk (review S13):** rotating per-message would break Plaud SoftAP parity; BLE session isolation + SoftAP preamble + BLE fallback mitigate exposure. Revisit only after owner hardware SoftAP confirms a compatible alternative. |
| Seq | Each WS binary frame uses its own seq (`seq ‖ packet` plaintext) |
| BLE | Active BLE session required; Wi-Fi is an acceleration path |

## Velum additions (safe)

- **Socket-level SoftAP bind** via SoftAP host bind / `Network.bindSocket` — never `bindProcessToNetwork`.
- **Preamble** first WS binary message (`VELUM-WIFI-v1:<bindToken>`) so a random SoftAP client is rejected.
- **BLE fallback** (`WifiTransferCoordinator` / `PlaudDeviceClient.downloadSessionPreferWifi`) on timeout, SoftAP failure, preamble mismatch, or cipher errors.

## Code map

- `WifiFastTransferSession` / `WifiTransferServer` / `WifiTransferCoordinator` / `WifiTransferPreamble`
- Android: `AndroidWifiSoftApJoiner`, `AndroidWifiTransferServer`
- JVM fake: `FakeWifiSoftApJoiner`, `FakeWifiDeviceClient`, `JavaWebsocketTransferServer`
- Sync entry: `PlaudDeviceClient.downloadSessionPreferWifi`
- Tests: `WifiFastPathE2ETest`, `WifiSoftApMockServerTest`

## Test instructions (#96)

Run `./gradlew :ble:jvmTest --rerun`. `WifiSoftApMockServerTest` starts `JavaWebsocketTransferServer` on an
ephemeral loopback port (`ServerSocket(0)`, connect-retry, no fixed ports) with `FakeWifiDeviceClient` as the device.

- **Simulated (JVM):** preamble accept/reject, sealed frames for both ciphers, seq ordering/replay, AEAD failure,
  full-file transfer with CRC (loopback MB/s is printed, not a hardware claim), mid-transfer disconnect and device
  never joining -> timeout -> BLE fallback with exact bytes, and port release after stop.
- **Android-side (Robolectric, `./gradlew :ble:testDebugUnitTest --rerun`):** `AndroidWifiTransferServerTest` runs the real
  `AndroidWifiTransferServer` on an ephemeral loopback port with a java-websocket device and Robolectric's `Network`
  shadow. It checks that each accepted socket is passed to `Network.bindSocket`, preamble accept/reject (close 1008),
  full-file CRC for both ciphers, mid-transfer disconnect and AEAD failure -> BLE fallback with exact bytes, and
  release of the SoftAP join and the port on success and failure. `AndroidWifiSoftApJoinerTest` checks the
  `ConnectivityManager` request/callback lifecycle: the process is never bound (`bindProcessToNetwork`), the callback
  is unregistered on leave, cancel and failed join, and the host address follows the bound network's link properties.
  No emulator is needed; these are simulated and make no hardware claim.
- **Not covered (stays in #8):** real SoftAP join, `Network.bindSocket` on a real `Network`, normal-network
  restoration, hardware throughput.
- **Nonce (#28):** `wifiFrameSealing_isDeterministicPerSecretAndSeq` characterizes sealing as deterministic per
  secret and sequence (and a roundtrip); the nonce policy and its rationale are tracked in #28 and the Nonce row above.

No Plaud servers are contacted.
