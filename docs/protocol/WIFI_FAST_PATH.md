# Wi-Fi fast transfer (Plaud SoftAP + WebSocket :8081)

**Status:** Implemented, not wired into the app: nothing in production calls `PlaudDeviceClient.downloadSessionPreferWifi`, `AndroidWifiTransferServer` or `AndroidWifiSoftApJoiner` yet (only tests do). JVM fake SoftAP E2E green (preamble, encrypted frame, BLE fallback). Confirm on real Note Pro hardware.

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

- **Socket-level SoftAP bind, attempted not proven:** `AndroidWifiTransferServer` tries `Network.bindSocket` on each accepted socket and never uses `bindProcessToNetwork`. On real Android the accepted-socket bind is expected to throw, so see the known platform limit in the test instructions below.
- **Early failure signals (#219):** servers publish `pathFailures()` codes. `wifi_peer_closed` (the authenticated device closed its socket) falls back to BLE at once, after a short `peerClosedGrace` that lets `assemble` finish from frames already received; `wifi_frame_overflow` (the consumer fell 64 frames behind and a frame was dropped, so the sequence has a gap) falls back immediately and closes the socket with 1013. Neither waits for the 45 s `wifiTimeout`.
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
  full-file transfer with CRC (loopback MB/s is printed and recorded below, not a hardware claim), mid-transfer disconnect and device
  never joining -> timeout -> BLE fallback with exact bytes, and port release after stop.
- **Android-side (Robolectric, `./gradlew :ble:testDebugUnitTest --rerun`):** `AndroidWifiTransferServerTest` runs the real
  `AndroidWifiTransferServer` on an ephemeral loopback port with a java-websocket device and Robolectric's `Network`
  shadow. `completeTransfer_onTheLiteralPort8081` repeats the complete transfer on `:8081`, the port a real device uses;
  if something else on the machine holds that port, the test is reported as skipped with that reason, and it never passes
  without running. It checks that each accepted socket is passed to `Network.bindSocket`, preamble accept/reject (close 1008),
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
- **Measured throughput, loopback only (2026-11-03):** this is the same machine talking to itself under the simulated
  path, not Wi-Fi, and says nothing about a Note Pro or a phone. Each test moves a 262,144-byte fixture, once per cipher,
  so the numbers mostly reflect start-up and a cold JVM. Three runs on a heavily loaded macOS arm64 machine (load above 600),
  in MB/s from the lines the tests print:

  | Test | ChaCha20-Poly1305 | AES-GCM |
  | --- | --- | --- |
  | `AndroidWifiTransferServerTest`, ephemeral port (Robolectric) | 3.73, 5.63, 4.57 | 2.10, 3.36, 3.57 |
  | `AndroidWifiTransferServerTest`, `:8081` (Robolectric) | 4.53, 3.29, 2.69 | 1.35, 1.83, 2.14 |
  | `WifiSoftApMockServerTest` (JVM) | 3.73, 2.81, 2.71 | 2.40, 3.25, 3.32 |

- **Not covered (stays in #8):** real SoftAP join, `Network.bindSocket` on a real `Network`, normal-network
  restoration, hardware throughput.
- **Nonce (#28):** `wifiFrameSealing_isDeterministicPerSecretAndSeq` characterizes sealing as deterministic per
  secret and sequence (and a roundtrip); the nonce policy and its rationale are tracked in #28 and the Nonce row above.

No Plaud servers are contacted.
