# Issue #96 read-only source coverage audit

- Issue: https://github.com/bloodf/velum/issues/96 (URL supplied by requester; issue body was not fetched because network access was prohibited).
- Lease: `f84b416d-eaad-40fe-a079-0186f6a2a434` (release remains with parent).
- Exact audited HEAD: `963663004e44f7a9b3816fc4c247a8053a2f6578`.
- Scope: Android SoftAP/WebSocket `:8081` mock-server test coverage versus current transfer implementation. Read-only; no tests, builds, emulators, network access, or source changes were performed.
- Tooling read: `AGENTS.md`, `docs/ai-tooling.md`. Initialized/synced only the ignored CodeGraph 1.6.2 index as that guide directs; indexed 930 files. CodeGraph query surfaced `AndroidWifiTransferServer`, `AndroidWifiTransferServerTest`, and `WifiSoftApMockServerTest`; every reported relationship below was checked against source. The ignored index is tool state, not an application change.

## Requirement-to-test map

| Behavior in current source | Existing evidence |
|---|---|
| Complete encrypted file, both cipher modes, CRC and cleanup | `AndroidWifiTransferServerTest.completeTransfer_matchesFixtureAndCrc_andReleasesEverything` (`ble/src/androidUnitTest/kotlin/dev/velum/ble/wifi/AndroidWifiTransferServerTest.kt:332-334`); literal port path `completeTransfer_onTheLiteralPort8081` (`:337-344`). JVM protocol counterpart is `WifiSoftApMockServerTest.completeTransfer_matchesFixtureAndCrc_bothCiphers` (`ble/src/jvmTest/kotlin/dev/velum/ble/wifi/WifiSoftApMockServerTest.kt:112-192`). |
| Per-socket SoftAP bind attempt / no-network receive / bind error reporting | Android tests `callsBindSocketOncePerAcceptedConnection` (`AndroidWifiTransferServerTest.kt:188-211`), `noNetwork_noBinding_serverStillDeliversFrames` (`:214-234`), and `AndroidWifiBindFailureTest.bindFailureIsReported_andTheDeviceIsStillServed` (`ble/src/androidUnitTest/kotlin/dev/velum/ble/wifi/AndroidWifiBindFailureTest.kt:78-109`). `docs/protocol/WIFI_FAST_PATH.md:18,52-57` correctly limits this to a simulated bind attempt, not proof of Android routing. |
| Preamble rejection, unauthenticated peer isolation | `preambleMismatch_closesWith1008_andFrameIsDropped` (`AndroidWifiTransferServerTest.kt:237-270`) and `strayClientConnectingAndClosing_doesNotRevokeTheAuthenticatedDevice` (`:273-328`); JVM suite also checks preamble mismatch and preamble-less frames (`WifiSoftApMockServerTest.kt:194-240`). |
| Peer close ordering, successful completion despite close, overflow | Android `aCompleteFileBeatsACloseThatFollowedIt_howeverSlowTheConsumer` (`:346-360`), `aCloseWhileAssembleFinishes_doesNotDiscardACompleteFile` (`:363-394`), `aCloseWithTheBufferExactlyFull_stillComesAfterItsFrames` (`:396-439`), and `slowConsumer_overflowsTheBuffer_failsThePathAndFallsBackToBle` (`:502-530`). JVM adds close-before-read and mid-transfer disconnect coverage (`WifiSoftApMockServerTest.kt:322-365`). |
| Mid-transfer peer loss and AEAD rejection fall back to exact BLE bytes; cleanup | Android `wifiDropsMidTransfer_fallsBackToBleWithExactBytes_andReleasesEverything` (`AndroidWifiTransferServerTest.kt:441-469`) and `aeadFailure_midTransfer_fallsBackToBle_andReleasesEverything` (`:471-500`). JVM also checks out-of-order/replay and tampered AEAD frames (`WifiSoftApMockServerTest.kt:242-312`). |
| No join/device response and occupied listener port | Android `deviceNeverJoins_timesOutToBle_andReleasesPort` (`AndroidWifiTransferServerTest.kt:533-550`) and `portInUse_failsStart` (`:556-566`); JVM coordinator coverage includes timeout and port-in-use fallback (`WifiSoftApMockServerTest.kt:405-425,468-487`). |

## Concrete untested fault callback

`AndroidWifiTransferServer`'s WebSocket server override at `ble/src/androidMain/kotlin/dev/velum/ble/wifi/AndroidWifiTransferServer.kt:106-112` only reacts to `onError` when `conn == null && !running` (listener startup failure). A callback carrying a non-null client connection has no branch: it emits no `pathFailures()` code, does not close/revoke the authenticated client, and does not otherwise notify `WifiTransferCoordinator`. The Android test class covers startup bind failure and peer-close/overflow paths, but contains no test inducing `onError(conn != null)`.

This is a source/test gap, not a claim about the frequency or exact conditions under which Java-WebSocket invokes that callback. A focused fault test should target `AndroidWifiTransferServerTest`: induce a per-client WebSocket error after authentication and during assembly; assert the path terminates via BLE fallback with exact full BLE bytes, then assert the SoftAP join and listener port are released. The implementation may need an explicit safe failure signal for the authenticated peer for that test to pass. Do not infer from existing loopback tests that a device or Android network has succeeded.

## Proposed narrow check (not run)

`./gradlew :ble:testDebugUnitTest --tests 'dev.velum.ble.wifi.AndroidWifiTransferServerTest'`

## Conclusion / limits

Existing source tests cover the principal transfer and fallback behaviors, so the former broad coverage need is substantially represented at this HEAD. The per-client `onError` path is the concrete remaining fault scenario identified in source. Test evidence is JVM/Robolectric only: `docs/protocol/WIFI_FAST_PATH.md:3,18,32-57` says production does not yet wire this path and that real SoftAP routing requires Note Pro hardware confirmation. Device proof and app wiring remain pending; no performance or device-success claim is made here. No tests/builds were run in this audit.
