# Wi-Fi fast transfer (Plaud SoftAP + WebSocket :8081)

**Status:** Implemented, not wired into the app: nothing in production calls `PlaudDeviceClient.downloadSessionPreferWifi`, `AndroidWifiTransferServer` or `AndroidWifiSoftApJoiner` yet (only tests do). JVM fake SoftAP E2E green (preamble, encrypted frame, BLE fallback). Confirm on real Note Pro hardware.

## Plaud APK 3.30.0 parity

| Piece | Behavior |
|-------|----------|
| SoftAP | Phone joins device SoftAP via `WifiNetworkSpecifier` (WPA2) |
| Transport | Phone runs **WebSocket server on `:8081`** (`org.java_websocket`, same as Plaud) |
| Crypto | Same BLE session **key / nonce / aad**; ChaCha20-Poly1305, or **AES-GCM** when cmd 138 bit `0x08` |
| Nonce | **Fixed for the whole session** — **required** for Note Pro; do not rotate. **Accepted risk on Wi-Fi only (review S13):** rotating per-message would break Plaud SoftAP parity. The SoftAP preamble limits who can connect on Wi-Fi, but it doesn't protect the frames. BLE uses the same fixed nonce, and BLE fallback isn't a mitigation; that exposure is open, pending owner decision. Revisit only after owner hardware SoftAP confirms a compatible alternative. |
| Seq | Each WS binary frame uses its own seq (`seq ‖ packet` plaintext) |
| BLE | Active BLE session required; Wi-Fi is an acceleration path |

## Velum additions (safe)

- **Socket-level SoftAP bind, attempted not proven:** `AndroidWifiTransferServer` tries `Network.bindSocket` on each accepted socket and never uses `bindProcessToNetwork`. On real Android the accepted-socket bind is expected to throw, so see the known platform limit in the test instructions below.
- **Preamble** first WS binary message (`VELUM-WIFI-v1:<bindToken>`) so a random SoftAP client is rejected.
- **Per-connection preamble, latest authenticated wins:** authorization is tracked per WebSocket connection. A valid preamble from a new connection replaces the current peer (the old socket is closed with 1001); an invalid one is closed with 1008; an unauthenticated close changes nothing. **Unverified (#8 hardware validation):** how the Note Pro reacts to a 1008/1001 close, and whether it ever opens parallel sockets.
- **BLE fallback** (`WifiTransferCoordinator` / `PlaudDeviceClient.downloadSessionPreferWifi`) on timeout, SoftAP failure, preamble mismatch, or cipher errors.

## Code map

- `WifiFastTransferSession` / `WifiTransferServer` / `WifiTransferCoordinator` / `WifiTransferPreamble`
- Android: `AndroidWifiSoftApJoiner`, `AndroidWifiTransferServer`
- JVM fakes (test-only `:ble-testing`): `FakeWifiSoftApJoiner`, `FakeWifiDeviceClient`; real loopback server: `JavaWebsocketTransferServer`
- Sync entry: `PlaudDeviceClient.downloadSessionPreferWifi`
- Tests: `WifiFastPathE2ETest`, `WifiSoftApMockServerTest`, `WifiFailureReasonTest` (JVM); `AndroidWifiTransferServerTest`, `AndroidWifiSoftApJoinerTest`, `AndroidWifiBindFailureTest` (Robolectric)

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
  `AndroidWifiBindFailureTest` makes `Network.bindSocket` throw like the platform: the failure is reported through
  `onBindFailure` and the device is still served; it also asserts the join request has a Wi-Fi transport and a
  `WifiNetworkSpecifier` with the expected SSID and passphrase. No emulator is needed; these are simulated and make no hardware claim.
- **Known platform limit (#8):** `AndroidWifiTransferServer` calls `Network.bindSocket` on each *accepted* socket. On
  real Android that is expected to throw (`Socket is connected`: the platform call reads the peer address), so the
  per-socket bind does not take effect there; the failure is reported through `onBindFailure` (logged by class only) and
  the connection is still served. The Robolectric tests only prove the call is made and the failure is reported, not that
  traffic is routed over the SoftAP network. Whoever wires Wi-Fi into the app must bind the listening side instead
  (SoftAP host address via `bindHost`) and confirm on a real Note Pro that traffic uses the SoftAP network; an address bind is not proven to be a network bind.
- **Not covered (stays in #8):** real SoftAP join, `Network.bindSocket` on a real `Network`, normal-network
  restoration, hardware throughput.
- **Nonce (#28):** `wifiFrameSealing_isDeterministicPerSecretAndSeq` characterizes sealing as deterministic per
  secret and sequence (and a roundtrip); the nonce policy and its rationale are tracked in #28 and the Nonce row above.

No Plaud servers are contacted.
