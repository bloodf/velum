# Issue 357 bounded read-only diagnosis

Lease: 185a1aad-3cb1-444f-8d0f-c4b2936ee060. Date: 2026-11-06.
Bound source: 963663004e44f7a9b3816fc4c247a8053a2f6578.
HEAD, local main and origin/main equal this SHA. GitHub connector fetch of
https://github.com/bloodf/velum/commits/main independently returned the same SHA.
Initial git status was clean. No source edits, tests, builds, physical networking,
process inspection, inference, emulators, external writes or subdelegation.
Product HOLD preserved. Parent releases lease.

## Result

357 remains a dependency-level final-frame delivery defect, confirmed by source
ordering, not reproduced in this unit. It is distinct from the already implemented
Velum close-marker ordering fixes. The actual device's behavior remains unknown.
No claim that the reported later CI failure has been causally diagnosed.

Issue fetched via GitHub connector: https://github.com/bloodf/velum/issues/357.
It reports selector EOF beating worker decode, abnormal close 1006, BLE fallback
and no mixed partial file. Comments identify a separate UNPROVEN write-interest
race and a possibly related happy-path CI failure; neither is proven here.
Do not reopen #349 or propose #323 work from this diagnosis.

## Exact trace at bound SHA

- gradle/libs.versions.toml:41,99 pins org.java-websocket:Java-WebSocket 1.5.7;
  ble/build.gradle.kts:51,57 uses it on JVM and Android.
- AndroidWifiTransferServer.kt:61 constructs the ordinary library server.
  :81-103 receives decoded binary messages, authenticates preamble, and tryEmits
  frames to a 64-slot SharedFlow; overflow explicitly fails the path.
  :67-75 clears the authenticated peer and emits PEER_CLOSED_FRAME after frames
  already delivered, suspending if full. With no consumer ever reading, it uses
  pathFailures instead. A consumer that has finished leaves assembly to finish.
- JavaWebsocketTransferServer.kt:48-91 implements the same JVM behavior.
- WifiFastTransfer.kt:186-192 turns the sentinel into wifi_peer_closed and opens
  ordinary frames in receive-sequence order.
- WifiTransferCoordinator.kt:71-97 races assemble against pathFailures under a
  timeout; :99-112 returns the complete bleDownload result on failure/timeout
  and stops the session in finally. This does not append partial Wi-Fi bytes.
- PlaudDeviceClient.kt:211-237 downloadSessionPreferWifi wraps that coordinator
  with downloadSession(sessionId,sizeBytes) as fallback. Bounded exact-symbol
  search of Kotlin in ble/core/composeApp found only its definition, no callers.
  Thus no demonstrated production integration in those source roots.

All abbreviated Kotlin paths above are under ble/src, in their platform's
kotlin/dev/velum/ble/wifi directory, except client/PlaudDeviceClient.kt.

## Upstream source check

Fetched the v1.5.7 tag through GitHub connector. These are upstream tag sources,
not a locally verified dependency binary or a new upstream commit binding.

https://github.com/TooTallNate/Java-WebSocket/blob/v1.5.7/src/main/java/org/java_websocket/server/WebSocketServer.java
  :520-535 selector doRead queues buf in conn.inQueue and schedules worker.
  :1102-1106 worker separately polls it; :1132-1138 calls ws.decode(buf).
https://github.com/TooTallNate/Java-WebSocket/blob/v1.5.7/src/main/java/org/java_websocket/SocketChannelIOHelper.java
  :41-47 read returning -1 immediately calls ws.eot().
https://github.com/TooTallNate/Java-WebSocket/blob/v1.5.7/src/main/java/org/java_websocket/WebSocketImpl.java
  :220-241 decode only calls decodeFrames in OPEN after handshake.
  :608-623 eot calls closeConnection(ABNORMAL_CLOSE,true) for two-way handshake
  without completed close state. No ordering barrier waits for queued decode.

Therefore an authenticated OPEN connection with final buffer queued can encounter
EOF first, close, and later skip decoding that buffer. Velum cannot restore bytes
it never receives. Another onClosing drain would not order the selector and worker
or safely own the library's partial-frame state.

## Existing gates and their limits

- AndroidWifiTransferServerTest.kt:347,364,398 already assert complete files beat
  close, close during final assembly preserves success, and full buffers preserve
  frame-before-sentinel order. :442 checks partial close yields exact BLE bytes
  and wifi_peer_closed; :472 covers AEAD failure; :503 covers overflow.
  These exercise downstream behavior and are not deterministic selector/worker
  EOF scheduling proofs. Several send then close directly.
- WifiSoftApBleFallbackTest.kt:201,208,217,253 covers rejected/complete frames,
  late close and full buffer; :299 checks real BLE download fallback exact bytes.
- WifiSoftApMockServerTest.kt:323 covers no-reader close; :341 disconnect fallback;
  :368 overflow; :406 timeout. Its launchDevice :154 and AEAD path :293 wait on
  DeliveredFrames before close, intentionally avoiding the library race.
- DeliveredFrames.kt:13-31 documents this exact race, subscribes undispatched,
  counts real frames and waits at most 5 seconds. It changes subscriber count,
  so must not be added to no-consumer or post-consumer-close tests.
- FakeWifiDeviceClient.kt:71-79 bounds close handshake at 5 seconds then hard
  closes; FakeWifiDeviceClientCloseTest.kt:66-74 asserts EOF for a silent peer.
  This covers a close hang, not guaranteed delivery of the final frame.
- WifiFastPathE2ETest.kt:101-153 coordinator_wifi_happy_path waits for subscription
  at :126, sends at :127, closes at :129 without delivery acknowledgement.
  Subscription readiness does not prove decode. Its 5-second assembler and
  BLE-must-not-run fallback can still fail under the dependency race.

## Smallest next task and deterministic gate

After explicit resumption, stabilize only coordinator_wifi_happy_path using a
bounded latch/deferred completed after its final sealed frame is received and
opened. Device waits for that signal before close; preserve existing subscription
barrier and propagate device errors. This removes a concrete unguarded test path;
it is NOT a production fix for 357. Existing DeliveredFrames is an alternative
only if its extra subscriber does not change the intended test semantics.

Specific gate: :ble:jvmTest --tests
dev.velum.ble.wifi.WifiFastPathE2ETest.coordinator_wifi_happy_path
must assert Outcome.Wifi, literal expected bytes, no BLE invocation, and bounded
device completion. Add an assertion that close was released only after the
receiver acknowledged those bytes. No sleeps or repeated stress runs as proof.

For an actual 357 fix, the smallest missing regression belongs at the dependency
decode/EOF boundary: authenticate first; park worker before final-buffer decode;
enqueue one known final binary frame; force EOF handling; release worker; assert
exactly one delivery of that payload before close. Current source predicts zero
delivery. A library patch/replacement must pass the same deterministic schedule.
A fake WifiTransferServer or invoking Velum callbacks directly cannot prove this.
No existing seam for this schedule was established; do not add a production
abstraction just to simulate downstream callbacks. Choosing a dependency patch
is separate authorized work. Retain current fallback gates above.

## Limits

Read AGENTS.md and docs/ai-tooling.md first. Referenced /Users/heitor/.codex/RTK.md
and Linux ~/.codex/RTK.md were absent. Applied concise-writing skill guidance.
CodeGraph query Wifi --limit 5 --json failed: CodeGraph not initialized in this
worktree. Did not initialize/sync/index under the light read-only budget; used
bounded rg and source reads instead. Shell git ls-remote failed DNS resolution
for github.com, but connector fetched current main, issue body/comments and
upstream source. No tests were run and no runtime pass is claimed.
